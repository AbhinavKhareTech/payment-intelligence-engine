package com.paymentintelligence.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentintelligence.model.MerchantRiskProfile;
import com.paymentintelligence.model.MerchantRiskProfile.RiskTier;
import com.paymentintelligence.model.PaymentTransaction;
import com.paymentintelligence.model.PaymentTransaction.TransactionChannel;
import com.paymentintelligence.model.RiskDecision;
import com.paymentintelligence.model.RiskDecision.Decision;
import com.paymentintelligence.model.RiskScore;
import com.paymentintelligence.model.RiskScore.RiskLevel;
import com.paymentintelligence.rules.RulesEngine;
import com.paymentintelligence.scoring.GenAiRiskScoringService;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RiskEvaluationController.class)
class RiskEvaluationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private GenAiRiskScoringService scoringService;

    @MockBean
    private RulesEngine rulesEngine;

    @MockBean
    private HazelcastInstance hazelcast;

    @Test
    @DisplayName("POST /api/v1/evaluate should return 200 with risk decision")
    void evaluateTransaction() throws Exception {
        PaymentTransaction txn = new PaymentTransaction(
                "TXN-001", "MERCH-001", "5411", "Test",
                BigDecimal.valueOf(100), "USD", "VISA", "1234",
                "US", "US", TransactionChannel.CARD_PRESENT,
                false, "fp-123", "1.2.3.4", Instant.now(), Map.of());

        RiskScore score = new RiskScore("TXN-001", 0.15, RiskLevel.LOW,
                "Low risk", List.of(), "test", 50L, Instant.now());

        RiskDecision decision = new RiskDecision("TXN-001", Decision.APPROVE,
                score, List.of(), null, Instant.now(), 10L);

        @SuppressWarnings("unchecked")
        IMap<String, MerchantRiskProfile> mockMap = mock(IMap.class);
        when(hazelcast.getMap("merchant-risk-profiles")).thenReturn(mockMap);
        when(mockMap.get("MERCH-001")).thenReturn(null);
        when(scoringService.scoreTransaction(any(), any())).thenReturn(score);
        when(rulesEngine.evaluate(any(), any(), any())).thenReturn(decision);

        mockMvc.perform(post("/api/v1/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(txn)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVE"))
                .andExpect(jsonPath("$.transaction_id").value("TXN-001"));
    }

    @Test
    @DisplayName("POST /api/v1/score should return 200 with risk score")
    void scoreTransaction() throws Exception {
        PaymentTransaction txn = new PaymentTransaction(
                "TXN-002", "MERCH-002", "5411", "Test",
                BigDecimal.valueOf(500), "USD", "VISA", "5678",
                "US", "GB", TransactionChannel.ECOMMERCE,
                false, "fp-456", "5.6.7.8", Instant.now(), Map.of());

        RiskScore score = new RiskScore("TXN-002", 0.45, RiskLevel.MEDIUM,
                "Cross-border ecommerce", List.of(), "test", 120L, Instant.now());

        @SuppressWarnings("unchecked")
        IMap<String, MerchantRiskProfile> mockMap = mock(IMap.class);
        when(hazelcast.getMap("merchant-risk-profiles")).thenReturn(mockMap);
        when(mockMap.get("MERCH-002")).thenReturn(null);
        when(scoringService.scoreTransaction(any(), any())).thenReturn(score);

        mockMvc.perform(post("/api/v1/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(txn)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0.45))
                .andExpect(jsonPath("$.risk_level").value("MEDIUM"));
    }

    @Test
    @DisplayName("GET /api/v1/merchant/{id}/profile should return 404 for unknown merchant")
    void unknownMerchantProfile() throws Exception {
        @SuppressWarnings("unchecked")
        IMap<String, MerchantRiskProfile> mockMap = mock(IMap.class);
        when(hazelcast.getMap("merchant-risk-profiles")).thenReturn(mockMap);
        when(mockMap.get("MERCH-UNKNOWN")).thenReturn(null);

        mockMvc.perform(get("/api/v1/merchant/MERCH-UNKNOWN/profile"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/merchant/{id}/profile should return profile when cached")
    void cachedMerchantProfile() throws Exception {
        MerchantRiskProfile profile = new MerchantRiskProfile(
                "MERCH-001", "Good Merchant", "5411", RiskTier.GREEN,
                0.005, BigDecimal.valueOf(50), 100, BigDecimal.valueOf(5000),
                Instant.now(), "US", Instant.now());

        @SuppressWarnings("unchecked")
        IMap<String, MerchantRiskProfile> mockMap = mock(IMap.class);
        when(hazelcast.getMap("merchant-risk-profiles")).thenReturn(mockMap);
        when(mockMap.get("MERCH-001")).thenReturn(profile);

        mockMvc.perform(get("/api/v1/merchant/MERCH-001/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risk_tier").value("GREEN"))
                .andExpect(jsonPath("$.merchant_name").value("Good Merchant"));
    }
}
