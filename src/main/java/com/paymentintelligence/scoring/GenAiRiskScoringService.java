package com.paymentintelligence.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentintelligence.model.MerchantRiskProfile;
import com.paymentintelligence.model.PaymentTransaction;
import com.paymentintelligence.model.RiskScore;
import com.paymentintelligence.model.RiskScore.RiskLevel;
import com.paymentintelligence.model.RiskScore.RiskSignal;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GenAI-powered transaction risk scoring service.
 *
 * This is the differentiating layer: it calls an LLM with structured
 * transaction context and receives a risk classification with reasoning.
 *
 * Architecture decisions:
 *   1. Circuit breaker wraps every LLM call. If the breaker opens,
 *      we fall back to deterministic scoring (rules-only).
 *   2. The prompt is structured to force JSON output. We do not rely
 *      on free-text parsing.
 *   3. Merchant risk profile from Hazelcast is injected into the prompt
 *      to give the model historical context.
 *   4. Every call is instrumented with latency, success/failure, and
 *      risk level distribution metrics.
 */
@Service
public class GenAiRiskScoringService {

    private static final Logger log = LoggerFactory.getLogger(GenAiRiskScoringService.class);

    private final WebClient genAiWebClient;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final Timer scoringLatencyTimer;
    private final Counter scoringSuccessCounter;
    private final Counter scoringFallbackCounter;

    @Value("${app.genai.model:claude-sonnet-4-20250514}")
    private String model;

    @Value("${app.genai.enabled:true}")
    private boolean genAiEnabled;

    public GenAiRiskScoringService(
            @Qualifier("genAiWebClient") WebClient genAiWebClient,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            MeterRegistry meterRegistry
    ) {
        this.genAiWebClient = genAiWebClient;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("genai-scoring");
        this.scoringLatencyTimer = Timer.builder("risk.scoring.latency")
                .description("GenAI scoring latency")
                .register(meterRegistry);
        this.scoringSuccessCounter = Counter.builder("risk.scoring.success")
                .description("Successful GenAI scoring calls")
                .register(meterRegistry);
        this.scoringFallbackCounter = Counter.builder("risk.scoring.fallback")
                .description("Fallback to deterministic scoring")
                .register(meterRegistry);
    }

    /**
     * Score a transaction using the GenAI model.
     * Falls back to deterministic scoring if GenAI is disabled or circuit is open.
     */
    public RiskScore scoreTransaction(PaymentTransaction txn, MerchantRiskProfile merchantProfile) {
        if (!genAiEnabled) {
            return deterministicFallback(txn, merchantProfile);
        }

        long startTime = System.currentTimeMillis();

        try {
            RiskScore score = circuitBreaker.executeSupplier(() ->
                    callGenAiApi(txn, merchantProfile, startTime));
            scoringSuccessCounter.increment();
            return score;
        } catch (Exception e) {
            log.warn("GenAI scoring failed for txn={}, falling back to deterministic: {}",
                    txn.transactionId(), e.getMessage());
            scoringFallbackCounter.increment();
            return deterministicFallback(txn, merchantProfile);
        } finally {
            scoringLatencyTimer.record(
                    java.time.Duration.ofMillis(System.currentTimeMillis() - startTime));
        }
    }

    private RiskScore callGenAiApi(PaymentTransaction txn,
                                    MerchantRiskProfile merchantProfile,
                                    long startTime) {
        String prompt = buildScoringPrompt(txn, merchantProfile);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 1024,
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                ),
                "system", SYSTEM_PROMPT
        );

        String response = genAiWebClient.post()
                .uri("/v1/messages")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return parseResponse(txn.transactionId(), response, startTime);
    }

    private RiskScore parseResponse(String transactionId, String response, long startTime) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("content").get(0).path("text");
            String text = content.asText();

            // Extract JSON from the response (handle markdown code fences)
            String json = text;
            if (text.contains("```json")) {
                json = text.substring(text.indexOf("```json") + 7, text.lastIndexOf("```")).trim();
            } else if (text.contains("```")) {
                json = text.substring(text.indexOf("```") + 3, text.lastIndexOf("```")).trim();
            }

            JsonNode scoreNode = objectMapper.readTree(json);

            double score = scoreNode.path("risk_score").asDouble(0.5);
            String reasoning = scoreNode.path("reasoning").asText("No reasoning provided");

            List<RiskSignal> signals = List.of();
            if (scoreNode.has("risk_signals") && scoreNode.get("risk_signals").isArray()) {
                signals = new java.util.ArrayList<>();
                for (JsonNode signalNode : scoreNode.get("risk_signals")) {
                    signals.add(new RiskSignal(
                            signalNode.path("type").asText(),
                            signalNode.path("description").asText(),
                            signalNode.path("weight").asDouble(0.0)
                    ));
                }
            }

            long latencyMs = System.currentTimeMillis() - startTime;

            return new RiskScore(
                    transactionId,
                    score,
                    RiskScore.levelFromScore(score),
                    reasoning,
                    signals,
                    model,
                    latencyMs,
                    Instant.now()
            );
        } catch (Exception e) {
            log.error("Failed to parse GenAI response for txn={}: {}", transactionId, e.getMessage());
            throw new RuntimeException("GenAI response parsing failed", e);
        }
    }

    /**
     * Deterministic fallback when GenAI is unavailable.
     * Uses simple heuristics based on transaction and merchant attributes.
     */
    RiskScore deterministicFallback(PaymentTransaction txn, MerchantRiskProfile merchantProfile) {
        double score = 0.1; // base score
        var signals = new java.util.ArrayList<RiskSignal>();

        // Cross-border transactions elevate risk
        if (txn.isCrossBorder()) {
            score += 0.15;
            signals.add(new RiskSignal("CROSS_BORDER", "Transaction crosses borders", 0.15));
        }

        // Card-not-present is higher risk
        if (txn.channel() == PaymentTransaction.TransactionChannel.CARD_NOT_PRESENT
                || txn.channel() == PaymentTransaction.TransactionChannel.ECOMMERCE) {
            score += 0.1;
            signals.add(new RiskSignal("CNP", "Card-not-present transaction", 0.1));
        }

        // High-value transaction
        if (txn.amount().doubleValue() > 5000) {
            score += 0.15;
            signals.add(new RiskSignal("HIGH_VALUE", "Transaction amount exceeds $5,000", 0.15));
        }

        // Merchant risk tier
        if (merchantProfile != null) {
            if (merchantProfile.riskTier() == MerchantRiskProfile.RiskTier.RED) {
                score += 0.25;
                signals.add(new RiskSignal("HIGH_RISK_MERCHANT", "Merchant in RED risk tier", 0.25));
            } else if (merchantProfile.riskTier() == MerchantRiskProfile.RiskTier.YELLOW) {
                score += 0.1;
                signals.add(new RiskSignal("ELEVATED_MERCHANT", "Merchant in YELLOW risk tier", 0.1));
            }
            if (merchantProfile.isChargebackExcessive()) {
                score += 0.2;
                signals.add(new RiskSignal("CHARGEBACK_RATE", "Merchant chargeback rate > 2%", 0.2));
            }
            if (merchantProfile.isNewMerchant()) {
                score += 0.05;
                signals.add(new RiskSignal("NEW_MERCHANT", "Merchant onboarded < 30 days", 0.05));
            }
        }

        score = Math.min(score, 1.0);

        return new RiskScore(
                txn.transactionId(),
                score,
                RiskScore.levelFromScore(score),
                "Deterministic fallback scoring (GenAI unavailable)",
                signals,
                "deterministic-v1",
                0L,
                Instant.now()
        );
    }

    private String buildScoringPrompt(PaymentTransaction txn, MerchantRiskProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("Evaluate the following payment transaction for fraud risk.\n\n");
        sb.append("TRANSACTION:\n");
        sb.append("  ID: ").append(txn.transactionId()).append("\n");
        sb.append("  Amount: ").append(txn.amount()).append(" ").append(txn.currency()).append("\n");
        sb.append("  Channel: ").append(txn.channel()).append("\n");
        sb.append("  Card Type: ").append(txn.cardType()).append("\n");
        sb.append("  Cardholder Country: ").append(txn.cardholderCountry()).append("\n");
        sb.append("  Merchant Country: ").append(txn.merchantCountry()).append("\n");
        sb.append("  MCC: ").append(txn.merchantCategoryCode()).append("\n");
        sb.append("  Cross-border: ").append(txn.isCrossBorder()).append("\n");
        sb.append("  Recurring: ").append(txn.isRecurring()).append("\n");
        sb.append("  Timestamp: ").append(txn.timestamp()).append("\n");

        if (profile != null) {
            sb.append("\nMERCHANT PROFILE:\n");
            sb.append("  Name: ").append(profile.merchantName()).append("\n");
            sb.append("  Risk Tier: ").append(profile.riskTier()).append("\n");
            sb.append("  Chargeback Rate: ").append(String.format("%.2f%%", profile.chargebackRate() * 100)).append("\n");
            sb.append("  Avg Txn Amount: ").append(profile.avgTransactionAmount()).append("\n");
            sb.append("  Daily Volume: ").append(profile.dailyVolume()).append("\n");
            sb.append("  Country: ").append(profile.country()).append("\n");
            sb.append("  New Merchant: ").append(profile.isNewMerchant()).append("\n");
        }

        sb.append("\nRespond ONLY with a JSON object (no markdown, no preamble):\n");
        sb.append("{\n");
        sb.append("  \"risk_score\": <float 0.0 to 1.0>,\n");
        sb.append("  \"reasoning\": \"<concise explanation>\",\n");
        sb.append("  \"risk_signals\": [{\"type\": \"<SIGNAL_TYPE>\", \"description\": \"<detail>\", \"weight\": <float>}]\n");
        sb.append("}\n");

        return sb.toString();
    }

    private static final String SYSTEM_PROMPT = """
            You are a payment fraud risk scoring engine. You evaluate payment transactions
            and return a risk score between 0.0 (no risk) and 1.0 (certain fraud).

            Your assessment must consider:
            1. Transaction amount relative to merchant's average
            2. Cross-border indicators
            3. Card-not-present risk
            4. Merchant risk profile and chargeback history
            5. Time-of-day patterns
            6. Velocity anomalies

            Respond ONLY with valid JSON. No markdown. No explanation outside the JSON.
            The reasoning field must be concise and suitable for regulatory audit trails.
            """;
}
