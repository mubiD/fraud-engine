package com.fraudengine.api.controller;

import com.fraudengine.api.dto.RuleDto;
import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.service.RuleManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RuleController.class)
class RuleControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    RuleManagementService ruleManagementService;

    @MockBean
    TransactionMapper mapper;

    @Test
    void getRules_returnsAllRules() throws Exception {
        FraudRule rule1 = mockRule("AmountThresholdRule", "1.0", 10, true);
        FraudRule rule2 = mockRule("VelocityRule", "1.0", 20, true);

        RuleDto dto1 = ruleDto("AmountThresholdRule", "1.0", 10, true);
        RuleDto dto2 = ruleDto("VelocityRule", "1.0", 20, true);

        when(ruleManagementService.getRules()).thenReturn(List.of(rule1, rule2));
        when(mapper.toDto(rule1)).thenReturn(dto1);
        when(mapper.toDto(rule2)).thenReturn(dto2);

        mockMvc.perform(get("/api/v1/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].ruleName").value("AmountThresholdRule"))
                .andExpect(jsonPath("$[0].ruleVersion").value("1.0"))
                .andExpect(jsonPath("$[0].priority").value(10))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[1].ruleName").value("VelocityRule"));
    }

    @Test
    void getRules_noRulesRegistered_returnsEmptyArray() throws Exception {
        when(ruleManagementService.getRules()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void getRules_disabledRule_isIncludedWithEnabledFalse() throws Exception {
        FraudRule disabledRule = mockRule("GeographicAnomalyRule", "1.0", 30, false);
        RuleDto dto = ruleDto("GeographicAnomalyRule", "1.0", 30, false);

        when(ruleManagementService.getRules()).thenReturn(List.of(disabledRule));
        when(mapper.toDto(disabledRule)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].enabled").value(false));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private FraudRule mockRule(String name, String version, int priority, boolean enabled) {
        FraudRule rule = mock(FraudRule.class);
        when(rule.getRuleName()).thenReturn(name);
        when(rule.getRuleVersion()).thenReturn(version);
        when(rule.getPriority()).thenReturn(priority);
        when(rule.isEnabled()).thenReturn(enabled);
        return rule;
    }

    private RuleDto ruleDto(String name, String version, int priority, boolean enabled) {
        RuleDto dto = new RuleDto();
        dto.setRuleName(name);
        dto.setRuleVersion(version);
        dto.setPriority(priority);
        dto.setEnabled(enabled);
        return dto;
    }
}
