package com.fraudengine.api.controller;

import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.config.SecurityConfig;
import com.fraudengine.engine.RuleEngine;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.repository.FraudAssessmentRepository;
import com.fraudengine.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StandaloneTransactionController.class)
@Import(SecurityConfig.class)
@ActiveProfiles({"local", "test"})
class StandaloneTransactionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean TransactionRepository transactionRepository;
    @MockBean FraudAssessmentRepository fraudAssessmentRepository;
    @MockBean RuleEngine ruleEngine;
    @MockBean TransactionMapper mapper;

    private Transaction savedTx;
    private FraudAssessment passedAssessment;
    private FraudAssessment fraudulentAssessment;

    @BeforeEach
    void setUp() {
        savedTx = Transaction.builder()
                .customerId("CUST-001")
                .merchantId("MERCH-WOOLWORTHS-ZA")
                .amount(new BigDecimal("250.00"))
                .currency("ZAR")
                .transactionType(TransactionType.CARD_PRESENT)
                .timestamp(Instant.now())
                .status(TransactionStatus.PENDING)
                .build();

        passedAssessment = FraudAssessment.builder()
                .transaction(savedTx)
                .fraudulent(false)
                .riskScore(10)
                .build();

        fraudulentAssessment = FraudAssessment.builder()
                .transaction(savedTx)
                .fraudulent(true)
                .riskScore(80)
                .build();

        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fraudAssessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── POST /api/v1/standalone/submit ──────────────────────────────────────

    @Test
    void submit_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/standalone/submit")
                        .contentType(APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(
                        "Request body is missing or malformed. Ensure the body is valid JSON and all required fields are present."));
    }

    @Test
    void submit_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/standalone/submit")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(
                        "Request body is missing or malformed. Ensure the body is valid JSON and all required fields are present."));
    }

    @Test
    void submit_lowercaseCurrency_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/standalone/submit")
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "customerId": "CUST-001",
                              "merchantId": "MERCH-WOOLWORTHS-ZA",
                              "amount": 250.00,
                              "currency": "zar"
                            }
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_latitudeOutOfRange_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/standalone/submit")
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "customerId": "CUST-001",
                              "merchantId": "MERCH-WOOLWORTHS-ZA",
                              "amount": 250.00,
                              "currency": "ZAR",
                              "latitude": -9999.0,
                              "longitude": 18.4241
                            }
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_longitudeOutOfRange_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/standalone/submit")
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "customerId": "CUST-001",
                              "merchantId": "MERCH-WOOLWORTHS-ZA",
                              "amount": 250.00,
                              "currency": "ZAR",
                              "latitude": -33.9249,
                              "longitude": 500.0
                            }
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_duplicateTransactionId_returnsExistingAssessmentWithoutReprocessing() throws Exception {
        UUID existingId = UUID.randomUUID();

        when(transactionRepository.findByIdOnly(existingId)).thenReturn(Optional.of(savedTx));
        when(fraudAssessmentRepository.findByTransactionId(existingId)).thenReturn(Optional.of(passedAssessment));
        when(mapper.toDto(passedAssessment)).thenReturn(new com.fraudengine.api.dto.FraudAssessmentDto());

        mockMvc.perform(post("/api/v1/standalone/submit")
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "transactionId": "%s",
                              "customerId": "CUST-001",
                              "merchantId": "MERCH-WOOLWORTHS-ZA",
                              "amount": 250.00,
                              "currency": "ZAR"
                            }
                            """.formatted(existingId)))
                .andExpect(status().isOk());

        verify(ruleEngine, never()).evaluate(any());
    }

    // ── POST /api/v1/standalone/stream ──────────────────────────────────────

    @Test
    void stream_allPass_returnsCorrectTotals() throws Exception {
        when(ruleEngine.evaluate(any())).thenReturn(passedAssessment);

        mockMvc.perform(post("/api/v1/standalone/stream").param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.passed").value(5))
                .andExpect(jsonPath("$.flagged").value(0));
    }

    @Test
    void stream_allFraudulent_flaggedCountMatchesTotal() throws Exception {
        when(ruleEngine.evaluate(any())).thenReturn(fraudulentAssessment);

        mockMvc.perform(post("/api/v1/standalone/stream").param("count", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.passed").value(0))
                .andExpect(jsonPath("$.flagged").value(3));
    }

    @Test
    void stream_countZero_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/standalone/stream").param("count", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stream_countAboveMax_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/standalone/stream").param("count", "10001"))
                .andExpect(status().isBadRequest());
    }
}
