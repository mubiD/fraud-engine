package com.fraudengine.api.controller;

import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.config.SecurityConfig;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Disposition;
import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.repository.FraudAssessmentRepository;
import com.fraudengine.repository.TransactionRepository;
import com.fraudengine.service.StandaloneTransactionProcessor;
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
    @MockBean StandaloneTransactionProcessor processor;
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
                .disposition(Disposition.CLEARED)
                .riskScore(10)
                .build();

        fraudulentAssessment = FraudAssessment.builder()
                .transaction(savedTx)
                .disposition(Disposition.FLAGGED)
                .riskScore(80)
                .build();
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
    void submit_customerIdOverColumnLimit_returns400() throws Exception {
        // customer_id is VARCHAR(64). Without this @Size bound, an oversized value used to
        // sail past validation and crash the INSERT with an unhandled
        // DataIntegrityViolationException (500) instead of a clean 400.
        mockMvc.perform(post("/api/v1/standalone/submit")
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "customerId": "%s",
                              "merchantId": "MERCH-WOOLWORTHS-ZA",
                              "amount": 250.00,
                              "currency": "ZAR"
                            }
                            """.formatted("C".repeat(65))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_merchantIdOverColumnLimit_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/standalone/submit")
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "customerId": "CUST-001",
                              "merchantId": "%s",
                              "amount": 250.00,
                              "currency": "ZAR"
                            }
                            """.formatted("M".repeat(65))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_categoryOverColumnLimit_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/standalone/submit")
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "customerId": "CUST-001",
                              "merchantId": "MERCH-WOOLWORTHS-ZA",
                              "amount": 250.00,
                              "currency": "ZAR",
                              "category": "%s"
                            }
                            """.formatted("R".repeat(65))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_locationOverColumnLimit_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/standalone/submit")
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "customerId": "CUST-001",
                              "merchantId": "MERCH-WOOLWORTHS-ZA",
                              "amount": 250.00,
                              "currency": "ZAR",
                              "location": "%s"
                            }
                            """.formatted("L".repeat(129))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_deviceFingerprintOverColumnLimit_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/standalone/submit")
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "customerId": "CUST-001",
                              "merchantId": "MERCH-WOOLWORTHS-ZA",
                              "amount": 250.00,
                              "currency": "ZAR",
                              "deviceFingerprint": "%s"
                            }
                            """.formatted("D".repeat(129))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_amountExceedsColumnPrecision_returns400() throws Exception {
        // amount is NUMERIC(19,4): 15 integer digits max. 16 used to sail past validation
        // and crash the INSERT with an unhandled DataIntegrityViolationException (500).
        mockMvc.perform(post("/api/v1/standalone/submit")
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "customerId": "CUST-001",
                              "merchantId": "MERCH-WOOLWORTHS-ZA",
                              "amount": 1000000000000000.00,
                              "currency": "ZAR"
                            }
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_racesAgainstConcurrentSubmit_returnsWinnersAssessmentInsteadOf500() throws Exception {
        // Simulates losing the check-then-insert race: the pre-check (findByIdOnly) sees
        // nothing, so submit() proceeds to process(), which fails because a concurrent
        // request for the same transactionId committed in the meantime. Recovery re-queries
        // and must find the winner's row this time.
        UUID sharedId = UUID.randomUUID();
        com.fraudengine.api.dto.FraudAssessmentDto winnerDto = new com.fraudengine.api.dto.FraudAssessmentDto();

        when(transactionRepository.findByIdOnly(sharedId))
                .thenReturn(Optional.empty())   // pre-check: doesn't exist yet
                .thenReturn(Optional.of(savedTx)); // recovery lookup: winner has since committed
        when(fraudAssessmentRepository.findByTransactionIdWithDetails(sharedId))
                .thenReturn(Optional.of(passedAssessment));
        when(mapper.toDto(passedAssessment)).thenReturn(winnerDto);
        when(processor.process(any())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException("duplicate key value violates unique constraint"));

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
                            """.formatted(sharedId)))
                .andExpect(status().isOk());
    }

    @Test
    void submit_duplicateTransactionId_returnsExistingAssessmentWithoutReprocessing() throws Exception {
        UUID existingId = UUID.randomUUID();

        when(transactionRepository.findByIdOnly(existingId)).thenReturn(Optional.of(savedTx));
        when(fraudAssessmentRepository.findByTransactionIdWithDetails(existingId)).thenReturn(Optional.of(passedAssessment));
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

        verify(processor, never()).process(any());
    }

    // ── POST /api/v1/standalone/stream ──────────────────────────────────────

    @Test
    void stream_allPass_returnsCorrectTotals() throws Exception {
        when(processor.process(any())).thenReturn(passedAssessment);

        mockMvc.perform(post("/api/v1/standalone/stream").param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.passed").value(5))
                .andExpect(jsonPath("$.pendingReview").value(0))
                .andExpect(jsonPath("$.flagged").value(0));
    }

    @Test
    void stream_allFraudulent_flaggedCountMatchesTotal() throws Exception {
        when(processor.process(any())).thenReturn(fraudulentAssessment);

        mockMvc.perform(post("/api/v1/standalone/stream").param("count", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.passed").value(0))
                .andExpect(jsonPath("$.pendingReview").value(0))
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
