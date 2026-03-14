package com.paymentintelligence.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Merchant risk profile cached in Hazelcast for sub-millisecond lookups
 * during transaction scoring.
 *
 * This profile aggregates historical merchant behaviour: transaction velocity,
 * chargeback rates, average ticket size, and onboarding risk tier. The profile
 * is updated asynchronously from the analytics pipeline and cached with a
 * TTL of 5 minutes (configurable).
 *
 * Implements Serializable for Hazelcast distributed storage.
 */
public record MerchantRiskProfile(

        @JsonProperty("merchant_id")
        String merchantId,

        @JsonProperty("merchant_name")
        String merchantName,

        @JsonProperty("mcc")
        String merchantCategoryCode,

        @JsonProperty("risk_tier")
        RiskTier riskTier,

        @JsonProperty("chargeback_rate")
        double chargebackRate,

        @JsonProperty("avg_transaction_amount")
        BigDecimal avgTransactionAmount,

        @JsonProperty("daily_transaction_count")
        int dailyTransactionCount,

        @JsonProperty("daily_volume")
        BigDecimal dailyVolume,

        @JsonProperty("onboarding_date")
        Instant onboardingDate,

        @JsonProperty("country")
        String country,

        @JsonProperty("last_updated")
        Instant lastUpdated

) implements Serializable {

    public enum RiskTier {
        GREEN,    // Low risk: established merchant, low chargebacks
        YELLOW,   // Moderate risk: new merchant or elevated signals
        RED,      // High risk: flagged patterns, requires manual review
        BLOCKED   // Merchant suspended or terminated
    }

    /**
     * Returns true if chargeback rate exceeds the 2% threshold
     * that triggers account pause (industry standard).
     */
    public boolean isChargebackExcessive() {
        return chargebackRate > 0.02;
    }

    /**
     * Returns true if the merchant was onboarded within the last 30 days.
     * New merchants receive elevated scrutiny.
     */
    public boolean isNewMerchant() {
        return onboardingDate != null
                && onboardingDate.isAfter(Instant.now().minusSeconds(30L * 24 * 3600));
    }
}
