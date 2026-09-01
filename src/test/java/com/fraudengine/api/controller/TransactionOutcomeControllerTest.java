package com.fraudengine.api.controller;

import com.fraudengine.api.dto.FraudAssessmentDto;
import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.config.SecurityConfig;
import com.fraudengine.exception.AssessmentAlreadyResolvedException;
import com.fraudengine.exception.ResourceNotFoundException;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.enums.AssessmentOutcome;
import com.fraudengine.service.AssessmentOutcomeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionOutcomeController.class)
@Import(SecurityConfig.class)
@ActiveProfiles({"local", "test"})
class TransactionOutcomeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean AssessmentOutcomeService outcomeService;
    @MockBean TransactionMapper mapper;

    static final UUID TRANSACTION_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    @Test
    void updateOutcome_confirmedFraud_returns200() throws Exception {
        FraudAssessment assessment = new FraudAssessment();
        assessment.setOutcome(AssessmentOutcome.CONFIRMED_FRAUD);

        when(outcomeService.updateOutcome(TRANSACTION_ID, AssessmentOutcome.CONFIRMED_FRAUD))
                .thenReturn(assessment);
        when(mapper.toDto(assessment)).thenReturn(new FraudAssessmentDto());

        mockMvc.perform(patch("/api/v1/transactions/{id}/outcome", TRANSACTION_ID)
                        .contentType(APPLICATION_JSON)
                        .content("""
                            { "outcome": "CONFIRMED_FRAUD" }
                            """))
                .andExpect(status().isOk());
    }

    @Test
    void updateOutcome_missingOutcome_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/transactions/{id}/outcome", TRANSACTION_ID)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateOutcome_invalidOutcomeValue_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/transactions/{id}/outcome", TRANSACTION_ID)
                        .contentType(APPLICATION_JSON)
                        .content("""
                            { "outcome": "NOT_A_REAL_OUTCOME" }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(
                        "Request body is missing or malformed. Ensure the body is valid JSON and all required fields are present."));
    }

    @Test
    void updateOutcome_malformedJson_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/transactions/{id}/outcome", TRANSACTION_ID)
                        .contentType(APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(
                        "Request body is missing or malformed. Ensure the body is valid JSON and all required fields are present."));
    }

    @Test
    void updateOutcome_unknownTransaction_returns404() throws Exception {
        when(outcomeService.updateOutcome(eq(TRANSACTION_ID), any()))
                .thenThrow(new ResourceNotFoundException("FraudAssessment for transaction", TRANSACTION_ID));

        mockMvc.perform(patch("/api/v1/transactions/{id}/outcome", TRANSACTION_ID)
                        .contentType(APPLICATION_JSON)
                        .content("""
                            { "outcome": "CONFIRMED_FRAUD" }
                            """))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateOutcome_alreadyResolved_returns409() throws Exception {
        when(outcomeService.updateOutcome(eq(TRANSACTION_ID), any()))
                .thenThrow(new AssessmentAlreadyResolvedException(TRANSACTION_ID, AssessmentOutcome.CONFIRMED_FRAUD));

        mockMvc.perform(patch("/api/v1/transactions/{id}/outcome", TRANSACTION_ID)
                        .contentType(APPLICATION_JSON)
                        .content("""
                            { "outcome": "FALSE_POSITIVE" }
                            """))
                .andExpect(status().isConflict());
    }
}
