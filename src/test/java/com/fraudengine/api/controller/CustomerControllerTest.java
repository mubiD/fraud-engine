package com.fraudengine.api.controller;

import com.fraudengine.api.dto.CustomerRiskSummaryDto;
import com.fraudengine.config.SecurityConfig;
import com.fraudengine.service.TransactionQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CustomerController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class CustomerControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TransactionQueryService queryService;

    private static final String CUSTOMER = "CUST-001";

    private CustomerRiskSummaryDto buildSummary() {
        CustomerRiskSummaryDto dto = new CustomerRiskSummaryDto();
        dto.setCustomerId(CUSTOMER);
        dto.setTotalTransactions(342L);
        dto.setFlaggedCount(4L);
        dto.setPassedCount(338L);
        dto.setFraudRate(1.17);
        dto.setHighestRiskScore(75);
        dto.setMostTriggeredRules(List.of("AmountThresholdRule", "VelocityRule", "TimeOfDayAnomalyRule"));
        dto.setFirstTransactionAt(Instant.parse("2025-01-15T08:00:00Z"));
        dto.setLastTransactionAt(Instant.parse("2026-07-23T09:00:00Z"));
        return dto;
    }

    @Test
    void getCustomerRiskSummary_returns200WithFullSummary() throws Exception {
        when(queryService.getCustomerRiskSummary(eq(CUSTOMER), isNull())).thenReturn(buildSummary());

        mockMvc.perform(get("/api/v1/customers/{customerId}/risk-summary", CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(CUSTOMER))
                .andExpect(jsonPath("$.data.totalTransactions").value(342))
                .andExpect(jsonPath("$.data.flaggedCount").value(4))
                .andExpect(jsonPath("$.data.passedCount").value(338))
                .andExpect(jsonPath("$.data.fraudRate").value(1.17))
                .andExpect(jsonPath("$.data.highestRiskScore").value(75))
                .andExpect(jsonPath("$.data.mostTriggeredRules", hasSize(3)))
                .andExpect(jsonPath("$.data.mostTriggeredRules[0]").value("AmountThresholdRule"))
                .andExpect(jsonPath("$.data.firstTransactionAt").value("2025-01-15T08:00:00Z"))
                .andExpect(jsonPath("$.data.lastTransactionAt").value("2026-07-23T09:00:00Z"));
    }

    private static final Instant SINCE = Instant.parse("2026-07-01T00:00:00Z");

    @Test
    void getCustomerRiskSummary_withSince_scopesMetrics() throws Exception {
        CustomerRiskSummaryDto scoped = buildSummary();
        scoped.setTotalTransactions(12L);
        scoped.setFlaggedCount(1L);
        scoped.setPassedCount(11L);

        when(queryService.getCustomerRiskSummary(eq(CUSTOMER), eq(SINCE))).thenReturn(scoped);

        mockMvc.perform(get("/api/v1/customers/{customerId}/risk-summary", CUSTOMER)
                        .param("since", "2026-07-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalTransactions").value(12));

        verify(queryService).getCustomerRiskSummary(CUSTOMER, SINCE);
    }

    @Test
    void getCustomerRiskSummary_malformedSince_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/customers/{customerId}/risk-summary", CUSTOMER)
                        .param("since", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getCustomerRiskSummary_noHistory_returnsZeroedSummary() throws Exception {
        CustomerRiskSummaryDto empty = new CustomerRiskSummaryDto();
        empty.setCustomerId(CUSTOMER);
        empty.setTotalTransactions(0L);
        empty.setFlaggedCount(0L);
        empty.setPassedCount(0L);
        empty.setFraudRate(0.0);
        empty.setHighestRiskScore(0);
        empty.setMostTriggeredRules(List.of());

        when(queryService.getCustomerRiskSummary(eq(CUSTOMER), isNull())).thenReturn(empty);

        mockMvc.perform(get("/api/v1/customers/{customerId}/risk-summary", CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalTransactions").value(0))
                .andExpect(jsonPath("$.data.fraudRate").value(0.0))
                .andExpect(jsonPath("$.data.mostTriggeredRules", hasSize(0)));
    }
}
