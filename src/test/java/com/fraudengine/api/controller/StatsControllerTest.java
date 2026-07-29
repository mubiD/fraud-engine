package com.fraudengine.api.controller;

import com.fraudengine.api.dto.FraudSummaryDto;
import com.fraudengine.api.dto.RuleBreakdownDto;
import com.fraudengine.service.TransactionQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

@WebMvcTest(StatsController.class)
@ActiveProfiles("test")
class StatsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TransactionQueryService queryService;

    private static final Instant FROM = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO   = Instant.parse("2026-07-31T23:59:59Z");

    private FraudSummaryDto buildSummary(Instant from, Instant to) {
        FraudSummaryDto dto = new FraudSummaryDto();
        dto.setFrom(from);
        dto.setTo(to);
        dto.setTotalAssessed(1000L);
        dto.setTotalFlagged(50L);
        dto.setTotalPassed(950L);
        dto.setFraudRate(5.0);
        dto.setRuleBreakdown(List.of(
                new RuleBreakdownDto("AmountThresholdRule", 30L, 60.0),
                new RuleBreakdownDto("VelocityRule", 20L, 40.0)
        ));
        return dto;
    }

    @Test
    void getFraudSummary_noDateRange_returns200WithSummary() throws Exception {
        when(queryService.getFraudSummary(isNull(), isNull()))
                .thenReturn(buildSummary(null, null));

        mockMvc.perform(get("/api/v1/stats/fraud-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAssessed").value(1000))
                .andExpect(jsonPath("$.totalFlagged").value(50))
                .andExpect(jsonPath("$.totalPassed").value(950))
                .andExpect(jsonPath("$.fraudRate").value(5.0))
                .andExpect(jsonPath("$.ruleBreakdown", hasSize(2)))
                .andExpect(jsonPath("$.ruleBreakdown[0].ruleName").value("AmountThresholdRule"))
                .andExpect(jsonPath("$.ruleBreakdown[0].count").value(30))
                .andExpect(jsonPath("$.ruleBreakdown[0].percentage").value(60.0));
    }

    @Test
    void getFraudSummary_withDateRange_passesInstantsToService() throws Exception {
        when(queryService.getFraudSummary(eq(FROM), eq(TO)))
                .thenReturn(buildSummary(FROM, TO));

        mockMvc.perform(get("/api/v1/stats/fraud-summary")
                        .param("from", "2026-07-01T00:00:00Z")
                        .param("to", "2026-07-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-07-01T00:00:00Z"))
                .andExpect(jsonPath("$.to").value("2026-07-31T23:59:59Z"));

        verify(queryService).getFraudSummary(FROM, TO);
    }

    @Test
    void getFraudSummary_malformedFrom_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/stats/fraud-summary").param("from", "2026-07-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFraudSummary_emptyBreakdown_returnsEmptyList() throws Exception {
        FraudSummaryDto empty = new FraudSummaryDto();
        empty.setTotalAssessed(0L);
        empty.setTotalFlagged(0L);
        empty.setTotalPassed(0L);
        empty.setFraudRate(0.0);
        empty.setRuleBreakdown(List.of());

        when(queryService.getFraudSummary(isNull(), isNull())).thenReturn(empty);

        mockMvc.perform(get("/api/v1/stats/fraud-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleBreakdown", hasSize(0)));
    }
}
