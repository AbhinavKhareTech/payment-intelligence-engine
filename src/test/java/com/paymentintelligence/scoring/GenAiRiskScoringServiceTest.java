package com.paymentintelligence.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paymentintelligence.model.MerchantRiskProfile;
import com.paymentintelligence.model.MerchantRiskProfile.RiskTier;
import com.paymentintelligence.model.PaymentTransaction;
import com.paymentintelligence.model.PaymentTransaction.TransactionChannel;
import com.paymentintelligence.model.RiskScore;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenAiRiskScoringServiceTest {

    private GenAiRiskScoringService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.ofDefaults());

        // Use a dummy WebClient (we only test deterministic fallback here)
        WebClient dummyClient = WebClient.builder().baseUrl("http://localhost:9999").build();

        service = new GenAiRiskScoringService(
                dummyClient, mapper, cbRegistry, new SimpleMeterRegistry());

        ReflectionTestUtils.setField(service, "genAiEnabled", false);
        ReflectionTestUtils.setField(service, "model", "test-model");
    }

    private PaymentTransaction domesticTxn(BigDecimal amount) {
        return new PaymentTransaction(
                "TXN-100", "MERCH-100", "5411", "Grocery Store",
                amount, "USD", "VISA", "1234", "US", "US",
                TransactionChannel.CARD_PRESENT, false,
                "fp-test", "192.168.1.1", Instant.now(), Map.of());
    }

    private PaymentTransaction crossBorderCnpTxn(BigDecimal amount) {
        return new PaymentTransaction(
                "TXN-101", "MERCH-101", "5411", "Online Store",
                amount, "USD", "VISA", "5678", "US", "NG",
                TransactionChannel.CARD_NOT_PRESENT, false,
                "fp-test2", "10.0.0.1", Instant.now(), Map.of());
    }

    private MerchantRiskProfile greenMerchant() {
        return new MerchantRiskProfile(
                "MERCH-100", "Good Merchant", "5411", RiskTier.GREEN,
                0.005, BigDecimal.valueOf(50), 100, BigDecimal.valueOf(5000),
                Instant.now().minusSeconds(365 * 24 * 3600), "US", Instant.now());
    }

    private MerchantRiskProfile redMerchant() {
        return new MerchantRiskProfile(
                "MERCH-102", "Flagged Merchant", "5411", RiskTier.RED,
                0.04, BigDecimal.valueOf(200), 300, BigDecimal.valueOf(60000),
                Instant.now().minusSeconds(15 * 24 * 3600), "NG", Instant.now());
    }

    @Test
    @DisplayName("Domestic card-present low-value should score LOW")
    void domesticLowValue() {
        RiskScore score = service.scoreTransaction(domesticTxn(BigDecimal.valueOf(25)), greenMerchant());

        assertThat(score.riskLevel()).isEqualTo(RiskScore.RiskLevel.LOW);
        assertThat(score.score()).isLessThan(0.3);
        assertThat(score.modelVersion()).isEqualTo("deterministic-v1");
    }

    @Test
    @DisplayName("Cross-border CNP high-value should score HIGH")
    void crossBorderCnpHighValue() {
        RiskScore score = service.scoreTransaction(
                crossBorderCnpTxn(BigDecimal.valueOf(8000)), greenMerchant());

        assertThat(score.score()).isGreaterThanOrEqualTo(0.3);
        assertThat(score.riskSignals()).isNotEmpty();
        assertThat(score.riskSignals().stream().map(RiskScore.RiskSignal::signalType))
                .contains("CROSS_BORDER", "CNP", "HIGH_VALUE");
    }

    @Test
    @DisplayName("RED merchant with excessive chargebacks should elevate score significantly")
    void redMerchantElevated() {
        RiskScore score = service.scoreTransaction(
                domesticTxn(BigDecimal.valueOf(100)), redMerchant());

        // RED merchant (+0.25) + excessive chargebacks (+0.2) + new merchant (+0.05) + base (0.1)
        assertThat(score.score()).isGreaterThanOrEqualTo(0.55);
        assertThat(score.riskSignals().stream().map(RiskScore.RiskSignal::signalType))
                .contains("HIGH_RISK_MERCHANT", "CHARGEBACK_RATE", "NEW_MERCHANT");
    }

    @Test
    @DisplayName("Null merchant profile should still produce a score")
    void nullMerchantProfile() {
        RiskScore score = service.scoreTransaction(domesticTxn(BigDecimal.valueOf(50)), null);

        assertThat(score).isNotNull();
        assertThat(score.transactionId()).isEqualTo("TXN-100");
        assertThat(score.score()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("Score should never exceed 1.0")
    void scoreCapped() {
        // Stack all risk factors
        RiskScore score = service.scoreTransaction(
                crossBorderCnpTxn(BigDecimal.valueOf(50000)), redMerchant());

        assertThat(score.score()).isLessThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("Reasoning indicates deterministic fallback")
    void reasoningIndicatesFallback() {
        RiskScore score = service.scoreTransaction(domesticTxn(BigDecimal.valueOf(50)), greenMerchant());
        assertThat(score.reasoning()).contains("Deterministic fallback");
    }
}
