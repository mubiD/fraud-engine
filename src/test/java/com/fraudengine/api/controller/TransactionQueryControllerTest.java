package com.fraudengine.api.controller;

import com.fraudengine.api.cursor.CursorUtils;
import com.fraudengine.api.dto.FraudAssessmentDto;
import com.fraudengine.api.dto.TransactionSummaryDto;
import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Disposition;
import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.service.TransactionQueryService;
import com.fraudengine.config.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransactionQueryController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class TransactionQueryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TransactionQueryService queryService;

    @MockBean
    TransactionMapper mapper;

    private static final UUID TX_ID     = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final UUID ASSESS_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final String CUSTOMER = "CUST-001";
    private static final Instant TS      = Instant.parse("2026-07-23T09:00:00Z");
    private static final Instant FROM    = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO      = Instant.parse("2026-07-31T23:59:59Z");

    private Transaction tx;
    private TransactionSummaryDto summaryDto;
    private FraudAssessment assessment;
    private FraudAssessmentDto assessmentDto;

    @BeforeEach
    void setUp() {
        tx = Transaction.builder()
                .id(TX_ID)
                .customerId(CUSTOMER)
                .merchantId("MERCH-001")
                .amount(new BigDecimal("250.00"))
                .currency("ZAR")
                .timestamp(TS)
                .transactionType(TransactionType.CARD_PRESENT)
                .status(TransactionStatus.ASSESSED)
                .build();

        summaryDto = new TransactionSummaryDto();
        summaryDto.setTransactionId(TX_ID);
        summaryDto.setCustomerId(CUSTOMER);
        summaryDto.setAmount(new BigDecimal("250.00"));
        summaryDto.setCurrency("ZAR");
        summaryDto.setTimestamp(TS);
        summaryDto.setStatus(TransactionStatus.ASSESSED);

        assessment = FraudAssessment.builder()
                .transaction(tx)
                .disposition(Disposition.FLAGGED)
                .riskScore(72)
                .build();
        assessment.setId(ASSESS_ID);
        assessment.setAssessedAt(TS.plusSeconds(1));
        assessment.setRuleViolations(List.of());

        assessmentDto = new FraudAssessmentDto();
        assessmentDto.setAssessmentId(ASSESS_ID);
        assessmentDto.setTransactionId(TX_ID);
        assessmentDto.setDisposition("FLAGGED");
        assessmentDto.setRiskScore(72);
        assessmentDto.setAssessedAt(TS.plusSeconds(1));
        assessmentDto.setViolations(List.of());
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/transactions
    // -----------------------------------------------------------------------

    @Test
    void getByCustomerId_returnsPagedSummaries() throws Exception {
        when(queryService.getByCustomerId(eq(CUSTOMER), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(tx), PageRequest.of(0, 20), false));
        when(mapper.toSummaryDto(tx)).thenReturn(summaryDto);

        mockMvc.perform(get("/api/v1/transactions").param("customerId", CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].transactionId").value(TX_ID.toString()))
                .andExpect(jsonPath("$.data[0].customerId").value(CUSTOMER))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void getByCustomerId_withNextPage_setsNextCursorAndHasMore() throws Exception {
        when(queryService.getByCustomerId(eq(CUSTOMER), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(tx), PageRequest.of(0, 20), true));
        when(mapper.toSummaryDto(tx)).thenReturn(summaryDto);

        mockMvc.perform(get("/api/v1/transactions").param("customerId", CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").value(CursorUtils.encode(TS, TX_ID)));
    }

    @Test
    void getByCustomerId_withCursorAndPageSize_passesParamsToService() throws Exception {
        String encodedCursor = CursorUtils.encode(TS, TX_ID);
        when(queryService.getByCustomerId(eq(CUSTOMER), isNull(), isNull(), eq(TS), eq(TX_ID), eq(5), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 5), false));

        mockMvc.perform(get("/api/v1/transactions")
                        .param("customerId", CUSTOMER)
                        .param("cursor", encodedCursor)
                        .param("pageSize", "5"))
                .andExpect(status().isOk());

        verify(queryService).getByCustomerId(CUSTOMER, null, null, TS, TX_ID, 5, "desc");
    }

    @Test
    void getByCustomerId_withDateRange_passesInstantsToService() throws Exception {
        when(queryService.getByCustomerId(eq(CUSTOMER), eq(FROM), eq(TO), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions")
                        .param("customerId", CUSTOMER)
                        .param("from", "2026-07-01T00:00:00Z")
                        .param("to", "2026-07-31T23:59:59Z"))
                .andExpect(status().isOk());

        verify(queryService).getByCustomerId(CUSTOMER, FROM, TO, null, null, 20, "desc");
    }

    @Test
    void getByCustomerId_pageSizeBelowMin_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .param("customerId", CUSTOMER)
                        .param("pageSize", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getByCustomerId_pageSizeAboveMax_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .param("customerId", CUSTOMER)
                        .param("pageSize", "1001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getByCustomerId_malformedCursor_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .param("customerId", CUSTOMER)
                        .param("cursor", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getByCustomerId_rateLimitExceeded_returns429WithRetryAfter() throws Exception {
        when(queryService.getByCustomerId(any(), any(), any(), any(), any(), anyInt(), anyString()))
                .thenThrow(RequestNotPermitted.createRequestNotPermitted(
                        io.github.resilience4j.ratelimiter.RateLimiter.ofDefaults("test")));

        mockMvc.perform(get("/api/v1/transactions").param("customerId", CUSTOMER))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(header().string("Retry-After", "10"));
    }

    @Test
    void getByCustomerId_toBeforeFrom_returns400() throws Exception {
        when(queryService.getByCustomerId(any(), any(), any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new IllegalArgumentException("'to' must not be before 'from'"));

        mockMvc.perform(get("/api/v1/transactions")
                        .param("customerId", CUSTOMER)
                        .param("from", "2026-07-31T00:00:00Z")
                        .param("to", "2026-07-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getByCustomerId_malformedFrom_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .param("customerId", CUSTOMER)
                        .param("from", "2026-07-01"))  // missing time component
                .andExpect(status().isBadRequest());
    }

    @Test
    void getByCustomerId_blankCustomerId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .param("customerId", "   "))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/transactions/{transactionId}
    // -----------------------------------------------------------------------

    @Test
    void getById_found_returns200WithDto() throws Exception {
        when(queryService.getById(TX_ID)).thenReturn(Optional.of(tx));
        when(mapper.toSummaryDto(tx)).thenReturn(summaryDto);

        mockMvc.perform(get("/api/v1/transactions/{id}", TX_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionId").value(TX_ID.toString()))
                .andExpect(jsonPath("$.data.customerId").value(CUSTOMER));
    }

    @Test
    void getById_notFound_returns404WithProblemDetail() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(queryService.getById(missingId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/transactions/{id}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Transaction " + missingId + " not found"));
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/transactions/{transactionId}/assessment
    // -----------------------------------------------------------------------

    @Test
    void getAssessment_found_returns200WithDto() throws Exception {
        when(queryService.getAssessment(TX_ID)).thenReturn(Optional.of(assessment));
        when(mapper.toDto(assessment)).thenReturn(assessmentDto);

        mockMvc.perform(get("/api/v1/transactions/{id}/assessment", TX_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assessmentId").value(ASSESS_ID.toString()))
                .andExpect(jsonPath("$.data.transactionId").value(TX_ID.toString()))
                .andExpect(jsonPath("$.data.disposition").value("FLAGGED"))
                .andExpect(jsonPath("$.data.riskScore").value(72));
    }

    @Test
    void getAssessment_notFound_returns404WithProblemDetail() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(queryService.getAssessment(missingId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/transactions/{id}/assessment", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Assessment for transaction " + missingId + " not found"));
    }

    @Test
    void getAssessment_invalidUuid_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/{id}/assessment", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/transactions/flagged
    // -----------------------------------------------------------------------

    @Test
    void getFlagged_noFilters_returns200() throws Exception {
        when(queryService.getFlagged(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(assessment), PageRequest.of(0, 20), false));
        when(mapper.toDto(assessment)).thenReturn(assessmentDto);

        mockMvc.perform(get("/api/v1/transactions/flagged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].disposition").value("FLAGGED"));
    }

    @Test
    void getFlagged_withCustomerId_passesCustomerIdToService() throws Exception {
        when(queryService.getFlagged(eq(CUSTOMER), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/flagged").param("customerId", CUSTOMER))
                .andExpect(status().isOk());

        verify(queryService).getFlagged(CUSTOMER, null, null, null, null, null, null, null, 20, "desc");
    }

    @Test
    void getFlagged_withRuleViolated_passesRuleToService() throws Exception {
        when(queryService.getFlagged(isNull(), eq("AmountThresholdRule"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/flagged").param("ruleViolated", "AmountThresholdRule"))
                .andExpect(status().isOk());

        verify(queryService).getFlagged(null, "AmountThresholdRule", null, null, null, null, null, null, 20, "desc");
    }

    @Test
    void getFlagged_withMinRiskScore_passesScoreToService() throws Exception {
        when(queryService.getFlagged(isNull(), isNull(), eq(50), isNull(), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/flagged").param("minRiskScore", "50"))
                .andExpect(status().isOk());

        verify(queryService).getFlagged(null, null, 50, null, null, null, null, null, 20, "desc");
    }

    @Test
    void getFlagged_withMaxRiskScore_passesScoreToService() throws Exception {
        when(queryService.getFlagged(isNull(), isNull(), isNull(), eq(65), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/flagged").param("maxRiskScore", "65"))
                .andExpect(status().isOk());

        verify(queryService).getFlagged(null, null, null, 65, null, null, null, null, 20, "desc");
    }

    @Test
    void getFlagged_withRiskScoreBand_passesBothScoresToService() throws Exception {
        when(queryService.getFlagged(isNull(), isNull(), eq(50), eq(65), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(assessment), PageRequest.of(0, 20), false));
        when(mapper.toDto(assessment)).thenReturn(assessmentDto);

        mockMvc.perform(get("/api/v1/transactions/flagged")
                        .param("minRiskScore", "50")
                        .param("maxRiskScore", "65"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));

        verify(queryService).getFlagged(null, null, 50, 65, null, null, null, null, 20, "desc");
    }

    @Test
    void getFlagged_withDateRange_passesInstantsToService() throws Exception {
        when(queryService.getFlagged(isNull(), isNull(), isNull(), isNull(), eq(FROM), eq(TO), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/flagged")
                        .param("from", "2026-07-01T00:00:00Z")
                        .param("to", "2026-07-31T23:59:59Z"))
                .andExpect(status().isOk());

        verify(queryService).getFlagged(null, null, null, null, FROM, TO, null, null, 20, "desc");
    }

    @Test
    void getFlagged_minRiskScoreAbove100_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/flagged").param("minRiskScore", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFlagged_maxRiskScoreBelow0_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/flagged").param("maxRiskScore", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getPassed_minRiskScoreAbove100_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/passed").param("minRiskScore", "999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFlagged_invalidSort_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/flagged").param("sort", "random"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFlagged_pageSizeBelowMin_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/flagged").param("pageSize", "0"))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/transactions/passed
    // -----------------------------------------------------------------------

    @Test
    void getPassed_returns200WithData() throws Exception {
        FraudAssessmentDto passedDto = new FraudAssessmentDto();
        passedDto.setAssessmentId(ASSESS_ID);
        passedDto.setTransactionId(TX_ID);
        passedDto.setDisposition("CLEARED");
        passedDto.setRiskScore(0);
        passedDto.setAssessedAt(TS.plusSeconds(1));
        passedDto.setViolations(List.of());

        FraudAssessment passedAssessment = FraudAssessment.builder()
                .transaction(tx)
                .disposition(Disposition.CLEARED)
                .riskScore(0)
                .build();
        passedAssessment.setId(ASSESS_ID);
        passedAssessment.setAssessedAt(TS.plusSeconds(1));
        passedAssessment.setRuleViolations(List.of());

        when(queryService.getPassed(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(passedAssessment), PageRequest.of(0, 20), false));
        when(mapper.toDto(passedAssessment)).thenReturn(passedDto);

        mockMvc.perform(get("/api/v1/transactions/passed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].disposition").value("CLEARED"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void getPassed_withCursor_passesTimestampAndIdToService() throws Exception {
        String encodedCursor = CursorUtils.encode(TS, ASSESS_ID);
        when(queryService.getPassed(isNull(), isNull(), isNull(), isNull(), eq(TS), eq(ASSESS_ID), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/passed").param("cursor", encodedCursor))
                .andExpect(status().isOk());

        verify(queryService).getPassed(null, null, null, null, TS, ASSESS_ID, 20, "desc");
    }

    @Test
    void getPassed_withCustomerIdAndDateRange_passesParamsToService() throws Exception {
        when(queryService.getPassed(eq(CUSTOMER), isNull(), eq(FROM), eq(TO), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/passed")
                        .param("customerId", CUSTOMER)
                        .param("from", "2026-07-01T00:00:00Z")
                        .param("to", "2026-07-31T23:59:59Z"))
                .andExpect(status().isOk());

        verify(queryService).getPassed(CUSTOMER, null, FROM, TO, null, null, 20, "desc");
    }

    @Test
    void getPassed_withMinRiskScore_passesScoreToService() throws Exception {
        // /passed is strictly CLEARED-only now: a "near miss" (elevated but not
        // flagged) transaction is PENDING_REVIEW, not passed; see the pending-review
        // section below for that scenario. This just exercises the minRiskScore filter
        // on genuinely cleared transactions.
        FraudAssessmentDto lowScoreDto = new FraudAssessmentDto();
        lowScoreDto.setAssessmentId(ASSESS_ID);
        lowScoreDto.setTransactionId(TX_ID);
        lowScoreDto.setDisposition("CLEARED");
        lowScoreDto.setRiskScore(8);
        lowScoreDto.setAssessedAt(TS.plusSeconds(1));
        lowScoreDto.setViolations(List.of());

        FraudAssessment lowScore = FraudAssessment.builder()
                .transaction(tx).disposition(Disposition.CLEARED).riskScore(8).build();
        lowScore.setId(ASSESS_ID);
        lowScore.setAssessedAt(TS.plusSeconds(1));
        lowScore.setRuleViolations(List.of());

        when(queryService.getPassed(isNull(), eq(5), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(lowScore), PageRequest.of(0, 20), false));
        when(mapper.toDto(lowScore)).thenReturn(lowScoreDto);

        mockMvc.perform(get("/api/v1/transactions/passed").param("minRiskScore", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].disposition").value("CLEARED"))
                .andExpect(jsonPath("$.data[0].riskScore").value(8));

        verify(queryService).getPassed(null, 5, null, null, null, null, 20, "desc");
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/transactions/pending-review
    // -----------------------------------------------------------------------

    @Test
    void getPendingReview_noFilters_returns200() throws Exception {
        FraudAssessmentDto pendingDto = new FraudAssessmentDto();
        pendingDto.setAssessmentId(ASSESS_ID);
        pendingDto.setTransactionId(TX_ID);
        pendingDto.setDisposition("PENDING_REVIEW");
        pendingDto.setRiskScore(15);
        pendingDto.setAssessedAt(TS.plusSeconds(1));
        pendingDto.setViolations(List.of());

        FraudAssessment pending = FraudAssessment.builder()
                .transaction(tx).disposition(Disposition.PENDING_REVIEW).riskScore(15).build();
        pending.setId(ASSESS_ID);
        pending.setAssessedAt(TS.plusSeconds(1));
        pending.setRuleViolations(List.of());

        when(queryService.getPendingReview(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(pending), PageRequest.of(0, 20), false));
        when(mapper.toDto(pending)).thenReturn(pendingDto);

        mockMvc.perform(get("/api/v1/transactions/pending-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].disposition").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data[0].riskScore").value(15));
    }

    @Test
    void getPendingReview_withCustomerId_passesCustomerIdToService() throws Exception {
        when(queryService.getPendingReview(eq(CUSTOMER), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/pending-review").param("customerId", CUSTOMER))
                .andExpect(status().isOk());

        verify(queryService).getPendingReview(CUSTOMER, null, null, null, null, null, null, null, 20, "desc");
    }

    @Test
    void getPendingReview_withRiskScoreBand_passesBothScoresToService() throws Exception {
        when(queryService.getPendingReview(isNull(), isNull(), eq(10), eq(49), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/pending-review")
                        .param("minRiskScore", "10")
                        .param("maxRiskScore", "49"))
                .andExpect(status().isOk());

        verify(queryService).getPendingReview(null, null, 10, 49, null, null, null, null, 20, "desc");
    }

    @Test
    void getPendingReview_withDateRange_passesInstantsToService() throws Exception {
        when(queryService.getPendingReview(isNull(), isNull(), isNull(), isNull(), eq(FROM), eq(TO), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/pending-review")
                        .param("from", "2026-07-01T00:00:00Z")
                        .param("to", "2026-07-31T23:59:59Z"))
                .andExpect(status().isOk());

        verify(queryService).getPendingReview(null, null, null, null, FROM, TO, null, null, 20, "desc");
    }

    @Test
    void getPendingReview_minRiskScoreAbove100_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/pending-review").param("minRiskScore", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getPendingReview_invalidSort_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/pending-review").param("sort", "random"))
                .andExpect(status().isBadRequest());
    }
}
