package com.fraudengine.service;

import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.repository.FraudAssessmentRepository;
import com.fraudengine.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final TransactionRepository transactionRepository;
    private final FraudAssessmentRepository assessmentRepository;

    public TransactionQueryService(TransactionRepository transactionRepository,
                                   FraudAssessmentRepository assessmentRepository) {
        this.transactionRepository = transactionRepository;
        this.assessmentRepository = assessmentRepository;
    }

    @Transactional(readOnly = true)
    public Slice<Transaction> getByCustomerId(String customerId, Instant cursor, int pageSize) {
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        return transactionRepository.findByCustomerIdBefore(customerId, cursor, PageRequest.of(0, size));
    }

    @Transactional(readOnly = true)
    public Optional<FraudAssessment> getAssessment(UUID transactionId) {
        return assessmentRepository.findByTransactionId(transactionId);
    }

    @Transactional(readOnly = true)
    public Slice<FraudAssessment> getFlagged(String customerId, String ruleViolated,
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

    @Transactional(readOnly = true)
    public Slice<FraudAssessment> getPassed(Instant cursor, int pageSize) {
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        return assessmentRepository.findPassedBefore(cursor, PageRequest.of(0, size));
    }
}
