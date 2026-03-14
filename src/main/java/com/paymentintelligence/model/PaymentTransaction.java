package com.paymentintelligence.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical payment transaction event consumed from Kafka
 * and evaluated through the risk scoring pipeline.
 *
 * Design note: this is intentionally an immutable record.
 * Mutation happens through new event emission, not in-place updates.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentTransaction(

        @JsonProperty("transaction_id")
        @NotBlank
        String transactionId,

        @JsonProperty("merchant_id")
        @NotBlank
        String merchantId,

        @JsonProperty("merchant_category_code")
        String merchantCategoryCode,

        @JsonProperty("merchant_name")
        String merchantName,

        @JsonProperty("amount")
        @NotNull @Positive
        BigDecimal amount,

        @JsonProperty("currency")
        @NotBlank
        String currency,

        @JsonProperty("card_type")
        String cardType,

        @JsonProperty("card_last_four")
        String cardLastFour,

        @JsonProperty("cardholder_country")
        String cardholderCountry,

        @JsonProperty("merchant_country")
        String merchantCountry,

        @JsonProperty("channel")
        TransactionChannel channel,

        @JsonProperty("is_recurring")
        boolean isRecurring,

        @JsonProperty("device_fingerprint")
        String deviceFingerprint,

        @JsonProperty("ip_address")
        String ipAddress,

        @JsonProperty("timestamp")
        @NotNull
        Instant timestamp,

        @JsonProperty("metadata")
        Map<String, String> metadata
) {

    public PaymentTransaction {
        if (transactionId == null || transactionId.isBlank()) {
            transactionId = UUID.randomUUID().toString();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    public enum TransactionChannel {
        CARD_PRESENT,
        CARD_NOT_PRESENT,
        ECOMMERCE,
        MOBILE,
        RECURRING,
        MOTO
    }

    /**
     * True when the transaction crosses borders (cardholder country
     * differs from merchant country).
     */
    public boolean isCrossBorder() {
        return cardholderCountry != null
                && merchantCountry != null
                && !cardholderCountry.equalsIgnoreCase(merchantCountry);
    }
}
