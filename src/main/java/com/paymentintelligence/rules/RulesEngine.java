package com.paymentintelligence.rules;

import com.paymentintelligence.model.MerchantRiskProfile;
import com.paymentintelligence.model.PaymentTransaction;
import com.paymentintelligence.model.RiskDecision;
import com.paymentintelligence.model.RiskDecision.Decision;
import com.paymentintelligence.model.RiskDecision.RuleResult;
import com.paymentintelligence.model.RiskScore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Deterministic rules engine that combines the GenAI risk score with
 * hard business rules to produce the final Accept / Review / Decline decision.
 *
 * Design philosophy:
 *   GenAI is a tool, not the decision-maker. The rules engine owns the
 *   final call. This separation is essential for two reasons:
 *     1. Regulatory explainability: every decline must be traceable to a
 *        specific rule or threshold, not just "the model said so."
 *     2. Operational safety: a hallucinating model cannot approve or decline
 *        a transaction that violates a hard business constraint.
 *
 * Rule evaluation order:
 *   1. Hard blocks (sanctions, blocked merchants) -- always DECLINE
 *   2. Velocity limits -- REVIEW or DECLINE based on severity
 *   3. Amount thresholds -- REVIEW for high-value transactions
 *   4. MCC restrictions -- DECLINE for prohibited categories
 *   5. GenAI score thresholds -- APPROVE / REVIEW / DECLINE
 *
 * A single DECLINE from any rule overrides GenAI. A REVIEW from any rule
 * escalates the transaction even if GenAI says low risk.
 */
@Service
public class RulesEngine {

    private static final Logger log = LoggerFactory.getLogger(RulesEngine.class);

    private final Counter approveCounter;
    private final Counter reviewCounter;
    private final Counter declineCounter;

    @Value("${app.rules.high-value-threshold:10000}")
    private double highValueThreshold;

    @Value("${app.rules.genai-decline-threshold:0.85}")
    private double genAiDeclineThreshold;

    @Value("${app.rules.genai-review-threshold:0.6}")
    private double genAiReviewThreshold;

    @Value("${app.rules.max-daily-velocity:500}")
    private int maxDailyVelocity;

    @Value("${app.rules.max-daily-volume:1000000}")
    private double maxDailyVolume;

    // Prohibited MCCs (high-risk categories requiring enhanced due diligence)
    private static final Set<String> PROHIBITED_MCCS = Set.of(
            "7995",  // Gambling
            "5967",  // Direct marketing - inbound teleservices
            "5966",  // Direct marketing - outbound teleservices
            "5912"   // Drug stores (controlled substances risk)
    );

    // High-risk MCCs (elevated scrutiny, not blocked)
    private static final Set<String> HIGH_RISK_MCCS = Set.of(
            "6051",  // Quasi-cash / money orders
            "6012",  // Financial institutions
            "4829",  // Wire transfers
            "7801",  // Internet gambling
            "5944"   // Jewelry stores
    );

    public RulesEngine(MeterRegistry meterRegistry) {
        this.approveCounter = Counter.builder("risk.decision")
                .tag("decision", "APPROVE").register(meterRegistry);
        this.reviewCounter = Counter.builder("risk.decision")
                .tag("decision", "REVIEW").register(meterRegistry);
        this.declineCounter = Counter.builder("risk.decision")
                .tag("decision", "DECLINE").register(meterRegistry);
    }

    /**
     * Evaluate a transaction against all rules and produce a final decision.
     */
    public RiskDecision evaluate(PaymentTransaction txn,
                                  RiskScore riskScore,
                                  MerchantRiskProfile merchantProfile) {
        long startTime = System.currentTimeMillis();
        List<RuleResult> results = new ArrayList<>();
        Decision finalDecision = Decision.APPROVE;

        // Rule 1: Blocked merchant
        if (merchantProfile != null
                && merchantProfile.riskTier() == MerchantRiskProfile.RiskTier.BLOCKED) {
            results.add(new RuleResult(
                    "RULE-001", "Blocked Merchant", true,
                    "Merchant " + txn.merchantId() + " is in BLOCKED state",
                    Decision.DECLINE));
            finalDecision = Decision.DECLINE;
        }

        // Rule 2: Prohibited MCC
        if (txn.merchantCategoryCode() != null
                && PROHIBITED_MCCS.contains(txn.merchantCategoryCode())) {
            results.add(new RuleResult(
                    "RULE-002", "Prohibited MCC", true,
                    "MCC " + txn.merchantCategoryCode() + " is in prohibited list",
                    Decision.DECLINE));
            finalDecision = Decision.DECLINE;
        }

        // Rule 3: Excessive chargeback rate
        if (merchantProfile != null && merchantProfile.isChargebackExcessive()) {
            results.add(new RuleResult(
                    "RULE-003", "Excessive Chargeback Rate", true,
                    String.format("Merchant chargeback rate %.2f%% exceeds 2%% threshold",
                            merchantProfile.chargebackRate() * 100),
                    Decision.REVIEW));
            if (finalDecision != Decision.DECLINE) {
                finalDecision = Decision.REVIEW;
            }
        }

        // Rule 4: High-value transaction
        if (txn.amount().compareTo(BigDecimal.valueOf(highValueThreshold)) > 0) {
            results.add(new RuleResult(
                    "RULE-004", "High Value Transaction", true,
                    String.format("Amount %s %s exceeds threshold %s",
                            txn.amount(), txn.currency(), highValueThreshold),
                    Decision.REVIEW));
            if (finalDecision != Decision.DECLINE) {
                finalDecision = Decision.REVIEW;
            }
        }

        // Rule 5: High-risk MCC with cross-border
        if (txn.merchantCategoryCode() != null
                && HIGH_RISK_MCCS.contains(txn.merchantCategoryCode())
                && txn.isCrossBorder()) {
            results.add(new RuleResult(
                    "RULE-005", "High-Risk MCC Cross-Border", true,
                    "High-risk MCC " + txn.merchantCategoryCode() + " combined with cross-border",
                    Decision.REVIEW));
            if (finalDecision != Decision.DECLINE) {
                finalDecision = Decision.REVIEW;
            }
        }

        // Rule 6: Velocity limit
        if (merchantProfile != null
                && merchantProfile.dailyTransactionCount() > maxDailyVelocity) {
            results.add(new RuleResult(
                    "RULE-006", "Velocity Limit Exceeded", true,
                    String.format("Daily txn count %d exceeds limit %d",
                            merchantProfile.dailyTransactionCount(), maxDailyVelocity),
                    Decision.REVIEW));
            if (finalDecision != Decision.DECLINE) {
                finalDecision = Decision.REVIEW;
            }
        }

        // Rule 7: Daily volume limit
        if (merchantProfile != null
                && merchantProfile.dailyVolume() != null
                && merchantProfile.dailyVolume().doubleValue() > maxDailyVolume) {
            results.add(new RuleResult(
                    "RULE-007", "Volume Limit Exceeded", true,
                    String.format("Daily volume %s exceeds limit %s",
                            merchantProfile.dailyVolume(), maxDailyVolume),
                    Decision.REVIEW));
            if (finalDecision != Decision.DECLINE) {
                finalDecision = Decision.REVIEW;
            }
        }

        // Rule 8: New merchant with high value
        if (merchantProfile != null && merchantProfile.isNewMerchant()
                && txn.amount().compareTo(BigDecimal.valueOf(highValueThreshold / 2)) > 0) {
            results.add(new RuleResult(
                    "RULE-008", "New Merchant High Value", true,
                    "New merchant (< 30 days) with transaction > 50% of high-value threshold",
                    Decision.REVIEW));
            if (finalDecision != Decision.DECLINE) {
                finalDecision = Decision.REVIEW;
            }
        }

        // Rule 9: GenAI score thresholds (applied last, after hard rules)
        if (finalDecision != Decision.DECLINE) {
            if (riskScore.score() >= genAiDeclineThreshold) {
                results.add(new RuleResult(
                        "RULE-009", "GenAI Critical Risk", true,
                        String.format("GenAI score %.3f >= decline threshold %.2f",
                                riskScore.score(), genAiDeclineThreshold),
                        Decision.DECLINE));
                finalDecision = Decision.DECLINE;
            } else if (riskScore.score() >= genAiReviewThreshold) {
                results.add(new RuleResult(
                        "RULE-009", "GenAI Elevated Risk", true,
                        String.format("GenAI score %.3f >= review threshold %.2f",
                                riskScore.score(), genAiReviewThreshold),
                        Decision.REVIEW));
                if (finalDecision != Decision.DECLINE) {
                    finalDecision = Decision.REVIEW;
                }
            }
        }

        // Determine review queue
        String reviewQueue = null;
        if (finalDecision == Decision.REVIEW) {
            reviewQueue = determineReviewQueue(txn, riskScore, results);
        }

        long processingTime = System.currentTimeMillis() - startTime;

        // Metrics
        switch (finalDecision) {
            case APPROVE -> approveCounter.increment();
            case REVIEW -> reviewCounter.increment();
            case DECLINE -> declineCounter.increment();
        }

        RiskDecision decision = new RiskDecision(
                txn.transactionId(),
                finalDecision,
                riskScore,
                results,
                reviewQueue,
                Instant.now(),
                processingTime
        );

        log.info("Decision for txn={}: {} (score={}, rules_triggered={}, latency={}ms)",
                txn.transactionId(), finalDecision, riskScore.score(),
                results.size(), processingTime);

        return decision;
    }

    private String determineReviewQueue(PaymentTransaction txn,
                                         RiskScore riskScore,
                                         List<RuleResult> results) {
        // Route to specialized queues based on triggered rules
        boolean hasFraudSignal = results.stream()
                .anyMatch(r -> r.ruleId().equals("RULE-009") && r.triggered());
        boolean hasComplianceSignal = results.stream()
                .anyMatch(r -> Set.of("RULE-002", "RULE-003", "RULE-005").contains(r.ruleId()));

        if (hasFraudSignal && riskScore.score() >= 0.75) {
            return "FRAUD_L2";
        }
        if (hasComplianceSignal) {
            return "COMPLIANCE";
        }
        return "FRAUD_L1";
    }
}
