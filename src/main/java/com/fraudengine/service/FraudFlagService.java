package com.fraudengine.service;

import com.fraudengine.model.FraudAssessment;
import com.fraudengine.repository.FraudAssessmentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class FraudFlagService {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final FraudAssessmentRepository assessmentRepository;

    public FraudFlagService(FraudAssessmentRepository assessmentRepository) {
        this.assessmentRepository = assessmentRepository;
    }

    @Transactional(readOnly = true)
    public Optional<FraudAssessment> getAssessment(UUID transactionId) {
        return assessmentRepository.findByTransactionId(transactionId);
    }

    @Transactional(readOnly = true)
    public Slice<FraudAssessment> getFraudFlags(String customerId, String ruleViolated,
                                                Integer minRiskScore, Instant cursor, int pageSize) {
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        PageRequest page = PageRequest.of(0, size);

        if (customerId != null) {
            return assessmentRepository.findFlaggedByCustomerBefore(customerId, cursor, page);
        }
        if (ruleViolated != null) {
            return assessmentRepository.findFlaggedByRuleBefore(ruleViolated, cursor, page);
        }
        if (minRiskScore != null) {
            return assessmentRepository.findFlaggedByMinRiskScoreBefore(minRiskScore, cursor, page);
        }
        return assessmentRepository.findFlaggedBefore(cursor, page);
    }
}
