package com.fraudengine.service;

import com.fraudengine.exception.AssessmentAlreadyResolvedException;
import com.fraudengine.exception.ResourceNotFoundException;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.enums.AssessmentOutcome;
import com.fraudengine.repository.FraudAssessmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssessmentOutcomeServiceTest {

    @Mock FraudAssessmentRepository fraudAssessmentRepository;

    AssessmentOutcomeService service;

    static final UUID TRANSACTION_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    @BeforeEach
    void setUp() {
        service = new AssessmentOutcomeService(fraudAssessmentRepository);
    }

    @Test
    void unresolvedAssessment_setToConfirmedFraud_isSavedAndReturned() {
        FraudAssessment assessment = new FraudAssessment();
        when(fraudAssessmentRepository.findByTransactionIdWithDetails(TRANSACTION_ID)).thenReturn(Optional.of(assessment));
        when(fraudAssessmentRepository.save(assessment)).thenReturn(assessment);

        FraudAssessment result = service.updateOutcome(TRANSACTION_ID, AssessmentOutcome.CONFIRMED_FRAUD);

        assertThat(result.getOutcome()).isEqualTo(AssessmentOutcome.CONFIRMED_FRAUD);
        verify(fraudAssessmentRepository).save(assessment);
    }

    @Test
    void unresolvedAssessment_setToFalsePositive_isSavedAndReturned() {
        FraudAssessment assessment = new FraudAssessment();
        when(fraudAssessmentRepository.findByTransactionIdWithDetails(TRANSACTION_ID)).thenReturn(Optional.of(assessment));
        when(fraudAssessmentRepository.save(assessment)).thenReturn(assessment);

        FraudAssessment result = service.updateOutcome(TRANSACTION_ID, AssessmentOutcome.FALSE_POSITIVE);

        assertThat(result.getOutcome()).isEqualTo(AssessmentOutcome.FALSE_POSITIVE);
    }

    @Test
    void unknownTransactionId_throwsResourceNotFound() {
        when(fraudAssessmentRepository.findByTransactionIdWithDetails(TRANSACTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateOutcome(TRANSACTION_ID, AssessmentOutcome.CONFIRMED_FRAUD))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(fraudAssessmentRepository, never()).save(any());
    }

    @Test
    void alreadyResolvedAssessment_throwsAlreadyResolved_andDoesNotOverwrite() {
        FraudAssessment assessment = new FraudAssessment();
        assessment.setOutcome(AssessmentOutcome.CONFIRMED_FRAUD);
        when(fraudAssessmentRepository.findByTransactionIdWithDetails(TRANSACTION_ID)).thenReturn(Optional.of(assessment));

        assertThatThrownBy(() -> service.updateOutcome(TRANSACTION_ID, AssessmentOutcome.FALSE_POSITIVE))
                .isInstanceOf(AssessmentAlreadyResolvedException.class);

        assertThat(assessment.getOutcome()).isEqualTo(AssessmentOutcome.CONFIRMED_FRAUD);
        verify(fraudAssessmentRepository, never()).save(any());
    }

    @Test
    void settingOutcomeBackToUnresolved_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.updateOutcome(TRANSACTION_ID, AssessmentOutcome.UNRESOLVED))
                .isInstanceOf(IllegalArgumentException.class);

        verify(fraudAssessmentRepository, never()).findByTransactionIdWithDetails(any());
    }
}
