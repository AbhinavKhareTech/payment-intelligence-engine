package com.paymentintelligence.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Final decisioning output from the rules engine.
 *
 * This is the authoritative record that determines whether a transaction
 * proceeds. The decision combines the GenAI risk score with deterministic
 * business rules (velocity limits, sanctions lists, MCC restrictions).
 *
 * Design decision: GenAI is a tool, not the decision-maker. The rules
 * engine owns the final call. This separation is essential for compliance
 * and for explaining decisions to regulators in plain language.
 */
public record RiskDecision(

        @JsonProperty("transaction_id")
        String transactionId,

        @JsonProperty("decision")
        Decision decision,

        @JsonProperty("risk_score")
        RiskScore riskScore,

        @JsonProperty("rules_triggered")
        List<RuleResult> rulesTriggered,

        @JsonProperty("review_queue")
        String reviewQueue,

        @JsonProperty("decided_at")
        Instant decidedAt,

        @JsonProperty("processing_time_ms")
        long processingTimeMs
) {

    public enum Decision {
        APPROVE,
        REVIEW,
        DECLINE
    }

    public record RuleResult(
            String ruleId,
            String ruleName,
            boolean triggered,
            String reason,
            Decision overrideDecision
    ) {}
}
