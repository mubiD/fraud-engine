package com.fraudengine.service;

import com.fraudengine.exception.AssessmentAlreadyResolvedException;
import com.fraudengine.exception.ResourceNotFoundException;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.enums.AssessmentOutcome;
import com.fraudengine.repository.FraudAssessmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AssessmentOutcomeService {

    private final FraudAssessmentRepository fraudAssessmentRepository;

    public AssessmentOutcomeService(FraudAssessmentRepository fraudAssessmentRepository) {
        this.fraudAssessmentRepository = fraudAssessmentRepository;
    }

    @Transactional
    public FraudAssessment updateOutcome(UUID transactionId, AssessmentOutcome newOutcome) {
        if (newOutcome == AssessmentOutcome.UNRESOLVED) {
            throw new IllegalArgumentException("outcome must be CONFIRMED_FRAUD or FALSE_POSITIVE");
        }

        FraudAssessment assessment = fraudAssessmentRepository.findByTransactionIdWithDetails(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("FraudAssessment for transaction", transactionId));

        // Atomic conditional UPDATE, not check-then-act: outcome is a one-time, audit-grade
        // disposition that feeds the model's ground truth, so two concurrent PATCHes racing
        // past an in-memory check and silently last-write-winning would be a real integrity
        // gap. resolveOutcomeIfUnresolved's own WHERE clause re-verifies UNRESOLVED at the
        // database; 0 rows updated means someone else resolved it since the read above.
        int rowsUpdated = fraudAssessmentRepository.resolveOutcomeIfUnresolved(assessment.getId(), newOutcome);
        if (rowsUpdated == 0) {
            AssessmentOutcome actual = fraudAssessmentRepository.findByTransactionIdWithDetails(transactionId)
                    .map(FraudAssessment::getOutcome)
                    .orElse(assessment.getOutcome());
            throw new AssessmentAlreadyResolvedException(transactionId, actual);
        }

        assessment.setOutcome(newOutcome);
        return assessment;
    }
}
