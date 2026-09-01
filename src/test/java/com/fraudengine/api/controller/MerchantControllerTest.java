package com.fraudengine.api.controller;

import com.fraudengine.api.cursor.CursorUtils;
import com.fraudengine.api.dto.FraudAssessmentDto;
import com.fraudengine.api.dto.MerchantRiskSummaryDto;
import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.config.SecurityConfig;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Disposition;
import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.service.TransactionQueryService;
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
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MerchantController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class MerchantControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TransactionQueryService queryService;

    @MockBean
    TransactionMapper mapper;

    private static final String MERCHANT  = "MERCH-NIKE-ZA";
    private static final UUID TX_ID       = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final UUID ASSESS_ID   = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final Instant TS       = Instant.parse("2026-07-23T09:00:00Z");
    private static final Instant FROM     = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO       = Instant.parse("2026-07-31T23:59:59Z");

    private FraudAssessment assessment;
    private FraudAssessmentDto assessmentDto;

    @BeforeEach
    void setUp() {
        Transaction tx = Transaction.builder()
                .id(TX_ID)
                .customerId("CUST-001")
                .merchantId(MERCHANT)
                .amount(new BigDecimal("7500.00"))
                .currency("ZAR")
                .timestamp(TS)
                .transactionType(TransactionType.CARD_PRESENT)
                .status(TransactionStatus.ASSESSED)
                .build();

        assessment = FraudAssessment.builder()
                .transaction(tx)
                .disposition(Disposition.FLAGGED)
                .riskScore(50)
                .build();
        assessment.setId(ASSESS_ID);
        assessment.setAssessedAt(TS.plusSeconds(1));
        assessment.setRuleViolations(List.of());

        assessmentDto = new FraudAssessmentDto();
        assessmentDto.setAssessmentId(ASSESS_ID);
        assessmentDto.setTransactionId(TX_ID);
        assessmentDto.setDisposition("FLAGGED");
        assessmentDto.setRiskScore(50);
        assessmentDto.setAssessedAt(TS.plusSeconds(1));
        assessmentDto.setViolations(List.of());
    }

    @Test
    void getFlaggedByMerchant_returns200WithData() throws Exception {
        when(queryService.getFlaggedByMerchant(eq(MERCHANT), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(assessment), PageRequest.of(0, 20), false));
        when(mapper.toDto(assessment)).thenReturn(assessmentDto);

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/flagged", MERCHANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].disposition").value("FLAGGED"))
                .andExpect(jsonPath("$.data[0].riskScore").value(50))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void getFlaggedByMerchant_withDateRange_passesInstantsToService() throws Exception {
        when(queryService.getFlaggedByMerchant(eq(MERCHANT), isNull(), isNull(), eq(FROM), eq(TO), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/flagged", MERCHANT)
                        .param("from", "2026-07-01T00:00:00Z")
                        .param("to", "2026-07-31T23:59:59Z"))
                .andExpect(status().isOk());

        verify(queryService).getFlaggedByMerchant(MERCHANT, null, null, FROM, TO, null, null, 20, "desc");
    }

    @Test
    void getFlaggedByMerchant_withNextPage_setsNextCursor() throws Exception {
        when(queryService.getFlaggedByMerchant(eq(MERCHANT), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(assessment), PageRequest.of(0, 20), true));
        when(mapper.toDto(assessment)).thenReturn(assessmentDto);

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/flagged", MERCHANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").value(CursorUtils.encode(TS.plusSeconds(1), ASSESS_ID)));
    }

    @Test
    void getFlaggedByMerchant_withRuleViolated_passesRuleToService() throws Exception {
        when(queryService.getFlaggedByMerchant(eq(MERCHANT), eq("VelocityRule"), isNull(), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(assessment), PageRequest.of(0, 20), false));
        when(mapper.toDto(assessment)).thenReturn(assessmentDto);

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/flagged", MERCHANT)
                        .param("ruleViolated", "VelocityRule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));

        verify(queryService).getFlaggedByMerchant(MERCHANT, "VelocityRule", null, null, null, null, null, 20, "desc");
    }

    @Test
    void getFlaggedByMerchant_withMinRiskScore_passesScoreToService() throws Exception {
        when(queryService.getFlaggedByMerchant(eq(MERCHANT), isNull(), eq(75), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(assessment), PageRequest.of(0, 20), false));
        when(mapper.toDto(assessment)).thenReturn(assessmentDto);

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/flagged", MERCHANT)
                        .param("minRiskScore", "75"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));

        verify(queryService).getFlaggedByMerchant(MERCHANT, null, 75, null, null, null, null, 20, "desc");
    }

    @Test
    void getFlaggedByMerchant_withRuleViolatedAndMinRiskScore_passesBothToService() throws Exception {
        when(queryService.getFlaggedByMerchant(eq(MERCHANT), eq("VelocityRule"), eq(60), isNull(), isNull(), isNull(), isNull(), eq(20), anyString()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/flagged", MERCHANT)
                        .param("ruleViolated", "VelocityRule")
                        .param("minRiskScore", "60"))
                .andExpect(status().isOk());

        verify(queryService).getFlaggedByMerchant(MERCHANT, "VelocityRule", 60, null, null, null, null, 20, "desc");
    }

    @Test
    void getFlaggedByMerchant_minRiskScoreAbove100_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/{merchantId}/flagged", MERCHANT)
                        .param("minRiskScore", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFlaggedByMerchant_minRiskScoreBelow0_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/{merchantId}/flagged", MERCHANT)
                        .param("minRiskScore", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFlaggedByMerchant_pageSizeBelowMin_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/{merchantId}/flagged", MERCHANT)
                        .param("pageSize", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFlaggedByMerchant_malformedFrom_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/{merchantId}/flagged", MERCHANT)
                        .param("from", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // GET /{merchantId}/risk-summary
    // -----------------------------------------------------------------------

    private MerchantRiskSummaryDto buildMerchantSummary() {
        MerchantRiskSummaryDto dto = new MerchantRiskSummaryDto();
        dto.setMerchantId(MERCHANT);
        dto.setTotalTransactions(1842L);
        dto.setFlaggedCount(12L);
        dto.setPassedCount(1830L);
        dto.setFraudRate(0.65);
        dto.setHighestRiskScore(85);
        dto.setUniqueCustomers(534L);
        dto.setMostTriggeredRules(List.of("AmountThresholdRule", "VelocityRule"));
        dto.setFirstTransactionAt(Instant.parse("2024-01-01T00:00:00Z"));
        dto.setLastTransactionAt(TS);
        return dto;
    }

    @Test
    void getMerchantRiskSummary_returns200WithFullSummary() throws Exception {
        when(queryService.getMerchantRiskSummary(eq(MERCHANT), isNull()))
                .thenReturn(buildMerchantSummary());

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/risk-summary", MERCHANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantId").value(MERCHANT))
                .andExpect(jsonPath("$.data.totalTransactions").value(1842))
                .andExpect(jsonPath("$.data.flaggedCount").value(12))
                .andExpect(jsonPath("$.data.passedCount").value(1830))
                .andExpect(jsonPath("$.data.fraudRate").value(0.65))
                .andExpect(jsonPath("$.data.highestRiskScore").value(85))
                .andExpect(jsonPath("$.data.uniqueCustomers").value(534))
                .andExpect(jsonPath("$.data.mostTriggeredRules", hasSize(2)))
                .andExpect(jsonPath("$.data.firstTransactionAt").value("2024-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.lastTransactionAt").value("2026-07-23T09:00:00Z"));
    }

    @Test
    void getMerchantRiskSummary_withSince_scopesMetrics() throws Exception {
        MerchantRiskSummaryDto scoped = buildMerchantSummary();
        scoped.setTotalTransactions(42L);
        scoped.setFlaggedCount(2L);
        scoped.setPassedCount(40L);

        when(queryService.getMerchantRiskSummary(eq(MERCHANT), eq(FROM))).thenReturn(scoped);

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/risk-summary", MERCHANT)
                        .param("since", "2026-07-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalTransactions").value(42));

        verify(queryService).getMerchantRiskSummary(MERCHANT, FROM);
    }

    @Test
    void getMerchantRiskSummary_malformedSince_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/{merchantId}/risk-summary", MERCHANT)
                        .param("since", "bad-date"))
                .andExpect(status().isBadRequest());
    }
}
