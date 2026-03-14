package com.paymentintelligence.rules;

import com.paymentintelligence.model.MerchantRiskProfile;
import com.paymentintelligence.model.MerchantRiskProfile.RiskTier;
import com.paymentintelligence.model.PaymentTransaction;
import com.paymentintelligence.model.PaymentTransaction.TransactionChannel;
import com.paymentintelligence.model.RiskDecision;
import com.paymentintelligence.model.RiskDecision.Decision;
import com.paymentintelligence.model.RiskScore;
import com.paymentintelligence.model.RiskScore.RiskLevel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RulesEngineTest {

    private RulesEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RulesEngine(new SimpleMeterRegistry());
        ReflectionTestUtils.setField(engine, "highValueThreshold", 10000.0);
        ReflectionTestUtils.setField(engine, "genAiDeclineThreshold", 0.85);
        ReflectionTestUtils.setField(engine, "genAiReviewThreshold", 0.6);
        ReflectionTestUtils.setField(engine, "maxDailyVelocity", 500);
        ReflectionTestUtils.setField(engine, "maxDailyVolume", 1000000.0);
    }

    private PaymentTransaction baseTxn(BigDecimal amount) {
        return new PaymentTransaction(
                "TXN-001", "MERCH-001", "5411", "Test Merchant",
                amount, "USD", "VISA", "1234", "US", "US",
                TransactionChannel.CARD_PRESENT, false,
                "fp-abc123", "192.168.1.1", Instant.now(), Map.of());
    }

    private PaymentTransaction crossBorderTxn(BigDecimal amount, String mcc) {
        return new PaymentTransaction(
                "TXN-002", "MERCH-002", mcc, "Foreign Merchant",
                amount, "USD", "VISA", "5678", "US", "GB",
                TransactionChannel.ECOMMERCE, false,
                "fp-xyz789", "10.0.0.1", Instant.now(), Map.of());
    }

    private MerchantRiskProfile greenMerchant() {
        return new MerchantRiskProfile(
                "MERCH-001", "Good Merchant", "5411", RiskTier.GREEN,
                0.005, BigDecimal.valueOf(50), 100, BigDecimal.valueOf(5000),
                Instant.now().minusSeconds(365 * 24 * 3600), "US", Instant.now());
    }

    private MerchantRiskProfile blockedMerchant() {
        return new MerchantRiskProfile(
                "MERCH-003", "Blocked Merchant", "5411", RiskTier.BLOCKED,
                0.05, BigDecimal.valueOf(100), 200, BigDecimal.valueOf(20000),
                Instant.now().minusSeconds(90 * 24 * 3600), "US", Instant.now());
    }

    private RiskScore lowRiskScore(String txnId) {
        return new RiskScore(txnId, 0.15, RiskLevel.LOW, "Low risk",
                List.of(), "test-model", 50L, Instant.now());
    }

    private RiskScore highRiskScore(String txnId) {
        return new RiskScore(txnId, 0.90, RiskLevel.CRITICAL, "Critical risk",
                List.of(), "test-model", 50L, Instant.now());
    }

    @Nested
    @DisplayName("Approve scenarios")
    class ApproveTests {

        @Test
        @DisplayName("Low-risk transaction with green merchant should APPROVE")
        void lowRiskGreenMerchant() {
            PaymentTransaction txn = baseTxn(BigDecimal.valueOf(50));
            RiskDecision decision = engine.evaluate(txn, lowRiskScore("TXN-001"), greenMerchant());

            assertThat(decision.decision()).isEqualTo(Decision.APPROVE);
            assertThat(decision.rulesTriggered()).isEmpty();
            assertThat(decision.reviewQueue()).isNull();
        }

        @Test
        @DisplayName("Transaction with null merchant profile and low score should APPROVE")
        void nullMerchantProfile() {
            PaymentTransaction txn = baseTxn(BigDecimal.valueOf(100));
            RiskDecision decision = engine.evaluate(txn, lowRiskScore("TXN-001"), null);

            assertThat(decision.decision()).isEqualTo(Decision.APPROVE);
        }
    }

    @Nested
    @DisplayName("Decline scenarios")
    class DeclineTests {

        @Test
        @DisplayName("Blocked merchant should always DECLINE")
        void blockedMerchantDeclines() {
            PaymentTransaction txn = baseTxn(BigDecimal.valueOf(10));
            RiskDecision decision = engine.evaluate(txn, lowRiskScore("TXN-001"), blockedMerchant());

            assertThat(decision.decision()).isEqualTo(Decision.DECLINE);
            assertThat(decision.rulesTriggered())
                    .anyMatch(r -> r.ruleId().equals("RULE-001"));
        }

        @Test
        @DisplayName("Prohibited MCC should DECLINE")
        void prohibitedMcc() {
            PaymentTransaction txn = new PaymentTransaction(
                    "TXN-003", "MERCH-001", "7995", "Casino",
                    BigDecimal.valueOf(500), "USD", "VISA", "1234",
                    "US", "US", TransactionChannel.CARD_PRESENT,
                    false, "fp-123", "1.2.3.4", Instant.now(), Map.of());

            RiskDecision decision = engine.evaluate(txn, lowRiskScore("TXN-003"), greenMerchant());
            assertThat(decision.decision()).isEqualTo(Decision.DECLINE);
            assertThat(decision.rulesTriggered())
                    .anyMatch(r -> r.ruleId().equals("RULE-002"));
        }

        @Test
        @DisplayName("Critical GenAI score should DECLINE")
        void criticalGenAiScore() {
            PaymentTransaction txn = baseTxn(BigDecimal.valueOf(100));
            RiskDecision decision = engine.evaluate(txn, highRiskScore("TXN-001"), greenMerchant());

            assertThat(decision.decision()).isEqualTo(Decision.DECLINE);
            assertThat(decision.rulesTriggered())
                    .anyMatch(r -> r.ruleId().equals("RULE-009"));
        }
    }

    @Nested
    @DisplayName("Review scenarios")
    class ReviewTests {

        @Test
        @DisplayName("High-value transaction should REVIEW")
        void highValueReview() {
            PaymentTransaction txn = baseTxn(BigDecimal.valueOf(15000));
            RiskDecision decision = engine.evaluate(txn, lowRiskScore("TXN-001"), greenMerchant());

            assertThat(decision.decision()).isEqualTo(Decision.REVIEW);
            assertThat(decision.rulesTriggered())
                    .anyMatch(r -> r.ruleId().equals("RULE-004"));
            assertThat(decision.reviewQueue()).isNotNull();
        }

        @Test
        @DisplayName("High-risk MCC with cross-border should REVIEW")
        void highRiskMccCrossBorder() {
            PaymentTransaction txn = crossBorderTxn(BigDecimal.valueOf(500), "6051");
            RiskDecision decision = engine.evaluate(txn, lowRiskScore("TXN-002"), greenMerchant());

            assertThat(decision.decision()).isEqualTo(Decision.REVIEW);
            assertThat(decision.rulesTriggered())
                    .anyMatch(r -> r.ruleId().equals("RULE-005"));
        }

        @Test
        @DisplayName("Excessive chargeback rate should REVIEW")
        void excessiveChargebacks() {
            MerchantRiskProfile profile = new MerchantRiskProfile(
                    "MERCH-004", "Risky Merchant", "5411", RiskTier.YELLOW,
                    0.035, BigDecimal.valueOf(100), 50, BigDecimal.valueOf(5000),
                    Instant.now().minusSeconds(180 * 24 * 3600), "US", Instant.now());

            PaymentTransaction txn = baseTxn(BigDecimal.valueOf(100));
            RiskDecision decision = engine.evaluate(txn, lowRiskScore("TXN-001"), profile);

            assertThat(decision.decision()).isEqualTo(Decision.REVIEW);
            assertThat(decision.rulesTriggered())
                    .anyMatch(r -> r.ruleId().equals("RULE-003"));
        }

        @Test
        @DisplayName("Elevated GenAI score should REVIEW")
        void elevatedGenAiScore() {
            RiskScore mediumScore = new RiskScore("TXN-001", 0.70, RiskLevel.HIGH,
                    "Elevated risk", List.of(), "test-model", 50L, Instant.now());

            PaymentTransaction txn = baseTxn(BigDecimal.valueOf(100));
            RiskDecision decision = engine.evaluate(txn, mediumScore, greenMerchant());

            assertThat(decision.decision()).isEqualTo(Decision.REVIEW);
        }
    }

    @Test
    @DisplayName("Hard rules override GenAI: blocked merchant with low score still DECLINES")
    void hardRulesOverrideGenAi() {
        PaymentTransaction txn = baseTxn(BigDecimal.valueOf(5));
        RiskDecision decision = engine.evaluate(txn, lowRiskScore("TXN-001"), blockedMerchant());
        assertThat(decision.decision()).isEqualTo(Decision.DECLINE);
    }

    @Test
    @DisplayName("Processing time is recorded")
    void processingTimeRecorded() {
        PaymentTransaction txn = baseTxn(BigDecimal.valueOf(50));
        RiskDecision decision = engine.evaluate(txn, lowRiskScore("TXN-001"), greenMerchant());
        assertThat(decision.processingTimeMs()).isGreaterThanOrEqualTo(0);
    }
}
