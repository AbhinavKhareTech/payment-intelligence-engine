package com.paymentintelligence.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Output of the GenAI risk scoring layer.
 *
 * The score is a probability between 0.0 (no risk) and 1.0 (certain fraud).
 * The reasoning field contains the LLM's chain-of-thought explanation,
 * which is critical for regulatory audit trails (explainability requirement).
 */
public record RiskScore(

        @JsonProperty("transaction_id")
        String transactionId,

        @JsonProperty("score")
        double score,

        @JsonProperty("risk_level")
        RiskLevel riskLevel,

        @JsonProperty("reasoning")
        String reasoning,

        @JsonProperty("risk_signals")
        List<RiskSignal> riskSignals,

        @JsonProperty("model_version")
        String modelVersion,

        @JsonProperty("latency_ms")
        long latencyMs,

        @JsonProperty("scored_at")
        Instant scoredAt
) {

    public enum RiskLevel {
        LOW,       // score < 0.3
        MEDIUM,    // 0.3 <= score < 0.6
        HIGH,      // 0.6 <= score < 0.85
        CRITICAL   // score >= 0.85
    }

    public record RiskSignal(
            String signalType,
            String description,
            double weight
    ) {}

    public static RiskLevel levelFromScore(double score) {
        if (score >= 0.85) return RiskLevel.CRITICAL;
        if (score >= 0.6) return RiskLevel.HIGH;
        if (score >= 0.3) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }
}
