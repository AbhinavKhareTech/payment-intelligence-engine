package com.paymentintelligence.api;

import com.paymentintelligence.model.MerchantRiskProfile;
import com.paymentintelligence.model.PaymentTransaction;
import com.paymentintelligence.model.RiskDecision;
import com.paymentintelligence.model.RiskScore;
import com.paymentintelligence.rules.RulesEngine;
import com.paymentintelligence.scoring.GenAiRiskScoringService;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for synchronous transaction risk evaluation.
 *
 * This endpoint exists alongside the Kafka-based async pipeline for
 * use cases that require a synchronous response (e.g., real-time
 * authorization decisioning where the issuer needs an inline response).
 *
 * Latency target: < 300ms p99 (including GenAI call).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Risk Evaluation", description = "Real-time payment risk scoring and decisioning")
public class RiskEvaluationController {

    private static final Logger log = LoggerFactory.getLogger(RiskEvaluationController.class);

    private final GenAiRiskScoringService scoringService;
    private final RulesEngine rulesEngine;
    private final HazelcastInstance hazelcast;

    public RiskEvaluationController(
            GenAiRiskScoringService scoringService,
            RulesEngine rulesEngine,
            HazelcastInstance hazelcast
    ) {
        this.scoringService = scoringService;
        this.rulesEngine = rulesEngine;
        this.hazelcast = hazelcast;
    }

    @PostMapping("/evaluate")
    @Operation(
            summary = "Evaluate transaction risk",
            description = "Synchronous risk evaluation combining GenAI scoring with deterministic rules. "
                    + "Returns Accept/Review/Decline decision with full audit trail.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Risk decision returned",
                            content = @Content(schema = @Schema(implementation = RiskDecision.class))),
                    @ApiResponse(responseCode = "400", description = "Invalid transaction payload"),
                    @ApiResponse(responseCode = "500", description = "Internal scoring error")
            }
    )
    public ResponseEntity<RiskDecision> evaluateTransaction(
            @Valid @RequestBody PaymentTransaction transaction
    ) {
        log.info("REST evaluation request for txn={} amount={} {}",
                transaction.transactionId(), transaction.amount(), transaction.currency());

        // Lookup merchant profile
        IMap<String, MerchantRiskProfile> profileCache =
                hazelcast.getMap("merchant-risk-profiles");
        MerchantRiskProfile merchantProfile = profileCache.get(transaction.merchantId());

        // Score
        RiskScore riskScore = scoringService.scoreTransaction(transaction, merchantProfile);

        // Decide
        RiskDecision decision = rulesEngine.evaluate(transaction, riskScore, merchantProfile);

        return ResponseEntity.ok(decision);
    }

    @PostMapping("/score")
    @Operation(
            summary = "Score transaction risk only",
            description = "Returns the GenAI risk score without running the rules engine. "
                    + "Useful for batch scoring or model evaluation."
    )
    public ResponseEntity<RiskScore> scoreTransaction(
            @Valid @RequestBody PaymentTransaction transaction
    ) {
        IMap<String, MerchantRiskProfile> profileCache =
                hazelcast.getMap("merchant-risk-profiles");
        MerchantRiskProfile merchantProfile = profileCache.get(transaction.merchantId());

        RiskScore riskScore = scoringService.scoreTransaction(transaction, merchantProfile);
        return ResponseEntity.ok(riskScore);
    }

    @GetMapping("/merchant/{merchantId}/profile")
    @Operation(
            summary = "Get merchant risk profile",
            description = "Returns the cached merchant risk profile from Hazelcast."
    )
    public ResponseEntity<MerchantRiskProfile> getMerchantProfile(
            @Parameter(description = "Merchant identifier")
            @PathVariable String merchantId
    ) {
        IMap<String, MerchantRiskProfile> profileCache =
                hazelcast.getMap("merchant-risk-profiles");
        MerchantRiskProfile profile = profileCache.get(merchantId);

        if (profile == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(profile);
    }

    @PutMapping("/merchant/{merchantId}/profile")
    @Operation(
            summary = "Update merchant risk profile",
            description = "Upserts a merchant risk profile into the distributed cache. "
                    + "In production, this would be driven by the analytics pipeline."
    )
    public ResponseEntity<MerchantRiskProfile> updateMerchantProfile(
            @PathVariable String merchantId,
            @Valid @RequestBody MerchantRiskProfile profile
    ) {
        IMap<String, MerchantRiskProfile> profileCache =
                hazelcast.getMap("merchant-risk-profiles");
        profileCache.put(merchantId, profile);

        log.info("Updated merchant profile for merchantId={} tier={}",
                merchantId, profile.riskTier());
        return ResponseEntity.ok(profile);
    }
}
