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

        FraudAssessment assessment = fraudAssessmentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("FraudAssessment for transaction", transactionId));

        if (assessment.getOutcome() != AssessmentOutcome.UNRESOLVED) {
            throw new AssessmentAlreadyResolvedException(transactionId, assessment.getOutcome());
        }

        assessment.setOutcome(newOutcome);
        return fraudAssessmentRepository.save(assessment);
    }
}
