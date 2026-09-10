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
    static final UUID ASSESSMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        service = new AssessmentOutcomeService(fraudAssessmentRepository);
    }

    private FraudAssessment unresolvedAssessment() {
        FraudAssessment assessment = new FraudAssessment();
        assessment.setId(ASSESSMENT_ID);
        return assessment;
    }

    @Test
    void unresolvedAssessment_setToConfirmedFraud_updatesAtomicallyAndReturns() {
        FraudAssessment assessment = unresolvedAssessment();
        when(fraudAssessmentRepository.findByTransactionIdWithDetails(TRANSACTION_ID)).thenReturn(Optional.of(assessment));
        when(fraudAssessmentRepository.resolveOutcomeIfUnresolved(ASSESSMENT_ID, AssessmentOutcome.CONFIRMED_FRAUD))
                .thenReturn(1);

        FraudAssessment result = service.updateOutcome(TRANSACTION_ID, AssessmentOutcome.CONFIRMED_FRAUD);

        assertThat(result.getOutcome()).isEqualTo(AssessmentOutcome.CONFIRMED_FRAUD);
        verify(fraudAssessmentRepository).resolveOutcomeIfUnresolved(ASSESSMENT_ID, AssessmentOutcome.CONFIRMED_FRAUD);
    }

    @Test
    void unresolvedAssessment_setToFalsePositive_updatesAtomicallyAndReturns() {
        FraudAssessment assessment = unresolvedAssessment();
        when(fraudAssessmentRepository.findByTransactionIdWithDetails(TRANSACTION_ID)).thenReturn(Optional.of(assessment));
        when(fraudAssessmentRepository.resolveOutcomeIfUnresolved(ASSESSMENT_ID, AssessmentOutcome.FALSE_POSITIVE))
                .thenReturn(1);

        FraudAssessment result = service.updateOutcome(TRANSACTION_ID, AssessmentOutcome.FALSE_POSITIVE);

        assertThat(result.getOutcome()).isEqualTo(AssessmentOutcome.FALSE_POSITIVE);
    }

    @Test
    void unknownTransactionId_throwsResourceNotFound() {
        when(fraudAssessmentRepository.findByTransactionIdWithDetails(TRANSACTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateOutcome(TRANSACTION_ID, AssessmentOutcome.CONFIRMED_FRAUD))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(fraudAssessmentRepository, never()).resolveOutcomeIfUnresolved(any(), any());
    }

    @Test
    void concurrentResolution_zeroRowsUpdated_throwsAlreadyResolved_reportingActualWinningOutcome() {
        // Simulates the exact race the atomic UPDATE closes: this caller's read sees
        // UNRESOLVED, but another request wins the conditional UPDATE first, so
        // resolveOutcomeIfUnresolved affects 0 rows here. The exception must report the
        // outcome that actually won (re-fetched), not the stale UNRESOLVED this caller read.
        FraudAssessment staleRead = unresolvedAssessment();
        FraudAssessment afterConcurrentWinner = unresolvedAssessment();
        afterConcurrentWinner.setOutcome(AssessmentOutcome.CONFIRMED_FRAUD);

        when(fraudAssessmentRepository.findByTransactionIdWithDetails(TRANSACTION_ID))
                .thenReturn(Optional.of(staleRead), Optional.of(afterConcurrentWinner));
        when(fraudAssessmentRepository.resolveOutcomeIfUnresolved(ASSESSMENT_ID, AssessmentOutcome.FALSE_POSITIVE))
                .thenReturn(0);

        assertThatThrownBy(() -> service.updateOutcome(TRANSACTION_ID, AssessmentOutcome.FALSE_POSITIVE))
                .isInstanceOf(AssessmentAlreadyResolvedException.class)
                .hasMessageContaining("CONFIRMED_FRAUD");
    }

    @Test
    void settingOutcomeBackToUnresolved_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.updateOutcome(TRANSACTION_ID, AssessmentOutcome.UNRESOLVED))
                .isInstanceOf(IllegalArgumentException.class);

        verify(fraudAssessmentRepository, never()).findByTransactionIdWithDetails(any());
        verify(fraudAssessmentRepository, never()).resolveOutcomeIfUnresolved(any(), any());
    }
}
