package com.fraudengine.api.controller;

import com.fraudengine.api.dto.FraudAssessmentDto;
import com.fraudengine.api.dto.TransactionSummaryDto;
import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.service.TransactionQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransactionQueryController.class)
class TransactionQueryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TransactionQueryService queryService;

    @MockBean
    TransactionMapper mapper;

    private static final UUID TX_ID    = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final UUID ASSESS_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final String CUSTOMER = "CUST-001";
    private static final Instant TS      = Instant.parse("2026-07-23T09:00:00Z");

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
                .fraudulent(true)
                .riskScore(72)
                .build();
        assessment.setId(ASSESS_ID);
        assessment.setAssessedAt(TS.plusSeconds(1));
        assessment.setRuleViolations(List.of());

        assessmentDto = new FraudAssessmentDto();
        assessmentDto.setAssessmentId(ASSESS_ID);
        assessmentDto.setTransactionId(TX_ID);
        assessmentDto.setFraudulent(true);
        assessmentDto.setRiskScore(72);
        assessmentDto.setAssessedAt(TS.plusSeconds(1));
        assessmentDto.setViolations(List.of());
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/transactions
    // -----------------------------------------------------------------------

    @Test
    void getByCustomerId_returnsPagedSummaries() throws Exception {
        when(queryService.getByCustomerId(eq(CUSTOMER), isNull(), eq(20)))
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
        when(queryService.getByCustomerId(eq(CUSTOMER), isNull(), eq(20)))
                .thenReturn(new SliceImpl<>(List.of(tx), PageRequest.of(0, 20), true));
        when(mapper.toSummaryDto(tx)).thenReturn(summaryDto);

        mockMvc.perform(get("/api/v1/transactions").param("customerId", CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").value("2026-07-23T09:00:00Z"));
    }

    @Test
    void getByCustomerId_withCursorAndPageSize_passesParamsToService() throws Exception {
        when(queryService.getByCustomerId(eq(CUSTOMER), eq(TS), eq(5)))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 5), false));

        mockMvc.perform(get("/api/v1/transactions")
                        .param("customerId", CUSTOMER)
                        .param("cursor", "2026-07-23T09:00:00Z")
                        .param("pageSize", "5"))
                .andExpect(status().isOk());

        verify(queryService).getByCustomerId(CUSTOMER, TS, 5);
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

    // -----------------------------------------------------------------------
    // GET /api/v1/transactions/{transactionId}/assessment
    // -----------------------------------------------------------------------

    @Test
    void getAssessment_found_returns200WithDto() throws Exception {
        when(queryService.getAssessment(TX_ID)).thenReturn(Optional.of(assessment));
        when(mapper.toDto(assessment)).thenReturn(assessmentDto);

        mockMvc.perform(get("/api/v1/transactions/{id}/assessment", TX_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessmentId").value(ASSESS_ID.toString()))
                .andExpect(jsonPath("$.transactionId").value(TX_ID.toString()))
                .andExpect(jsonPath("$.fraudulent").value(true))
                .andExpect(jsonPath("$.riskScore").value(72));
    }

    @Test
    void getAssessment_notFound_returns404() throws Exception {
        when(queryService.getAssessment(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/transactions/{id}/assessment", UUID.randomUUID()))
                .andExpect(status().isNotFound());
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
        when(queryService.getFlagged(isNull(), isNull(), isNull(), isNull(), eq(20)))
                .thenReturn(new SliceImpl<>(List.of(assessment), PageRequest.of(0, 20), false));
        when(mapper.toDto(assessment)).thenReturn(assessmentDto);

        mockMvc.perform(get("/api/v1/transactions/flagged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].fraudulent").value(true));
    }

    @Test
    void getFlagged_withCustomerId_passesCustomerIdToService() throws Exception {
        when(queryService.getFlagged(eq(CUSTOMER), isNull(), isNull(), isNull(), eq(20)))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/flagged").param("customerId", CUSTOMER))
                .andExpect(status().isOk());

        verify(queryService).getFlagged(CUSTOMER, null, null, null, 20);
    }

    @Test
    void getFlagged_withRuleViolated_passesRuleToService() throws Exception {
        when(queryService.getFlagged(isNull(), eq("AmountThresholdRule"), isNull(), isNull(), eq(20)))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/flagged").param("ruleViolated", "AmountThresholdRule"))
                .andExpect(status().isOk());

        verify(queryService).getFlagged(null, "AmountThresholdRule", null, null, 20);
    }

    @Test
    void getFlagged_withMinRiskScore_passesScoreToService() throws Exception {
        when(queryService.getFlagged(isNull(), isNull(), eq(50), isNull(), eq(20)))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/flagged").param("minRiskScore", "50"))
                .andExpect(status().isOk());

        verify(queryService).getFlagged(null, null, 50, null, 20);
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
        passedDto.setFraudulent(false);
        passedDto.setRiskScore(0);
        passedDto.setAssessedAt(TS.plusSeconds(1));
        passedDto.setViolations(List.of());

        FraudAssessment passedAssessment = FraudAssessment.builder()
                .transaction(tx)
                .fraudulent(false)
                .riskScore(0)
                .build();
        passedAssessment.setId(ASSESS_ID);
        passedAssessment.setAssessedAt(TS.plusSeconds(1));
        passedAssessment.setRuleViolations(List.of());

        when(queryService.getPassed(isNull(), eq(20)))
                .thenReturn(new SliceImpl<>(List.of(passedAssessment), PageRequest.of(0, 20), false));
        when(mapper.toDto(passedAssessment)).thenReturn(passedDto);

        mockMvc.perform(get("/api/v1/transactions/passed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].fraudulent").value(false))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void getPassed_withCursor_passesInstantToService() throws Exception {
        when(queryService.getPassed(eq(TS), eq(20)))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/transactions/passed").param("cursor", "2026-07-23T09:00:00Z"))
                .andExpect(status().isOk());

        verify(queryService).getPassed(TS, 20);
    }
}
