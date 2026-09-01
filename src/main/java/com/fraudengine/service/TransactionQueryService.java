package com.fraudengine.service;

import com.fraudengine.api.dto.CustomerRiskSummaryDto;
import com.fraudengine.api.dto.FraudSummaryDto;
import com.fraudengine.api.dto.MerchantRiskSummaryDto;
import com.fraudengine.api.dto.RuleBreakdownDto;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.repository.FraudAssessmentRepository;
import com.fraudengine.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final TransactionRepository transactionRepository;
    private final FraudAssessmentRepository assessmentRepository;
    private final RuleManagementService ruleManagementService;

    public TransactionQueryService(TransactionRepository transactionRepository,
                                   FraudAssessmentRepository assessmentRepository,
                                   RuleManagementService ruleManagementService) {
        this.transactionRepository = transactionRepository;
        this.assessmentRepository = assessmentRepository;
        this.ruleManagementService = ruleManagementService;
    }

    private void validateDateRange(Instant from, Instant to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException(
                    "'to' (" + to + ") must not be before 'from' (" + from + ")");
        }
    }

    private void validateRuleViolated(String ruleViolated) {
        if (ruleViolated == null) return;
        Set<String> valid = ruleManagementService.getRules().stream()
                .map(r -> r.getRuleName())
                .collect(Collectors.toSet());
        if (!valid.contains(ruleViolated)) {
            throw new IllegalArgumentException(
                    "Unknown ruleViolated value '" + ruleViolated + "'. Valid values: " + valid);
        }
    }

    @Transactional(readOnly = true)
    public Slice<Transaction> getByCustomerId(String customerId, Instant from, Instant to,
                                              Instant cursorTimestamp, UUID cursorId, int pageSize,
                                              String sort) {
        validateDateRange(from, to);
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        if ("asc".equals(sort)) {
            return transactionRepository.findByCustomerInRangeAsc(customerId, from, to, cursorTimestamp, cursorId,
                    PageRequest.of(0, size));
        }
        return transactionRepository.findByCustomerInRange(customerId, from, to, cursorTimestamp, cursorId,
                PageRequest.of(0, size));
    }

    @Transactional(readOnly = true)
    public Optional<Transaction> getById(UUID transactionId) {
        return transactionRepository.findByIdOnly(transactionId);
    }

    @Transactional(readOnly = true)
    public Optional<FraudAssessment> getAssessment(UUID transactionId) {
        return assessmentRepository.findByTransactionId(transactionId);
    }

    @Transactional(readOnly = true)
    public Slice<FraudAssessment> getFlagged(String customerId, String ruleViolated,
                                             Integer minRiskScore, Integer maxRiskScore,
                                             Instant from, Instant to,
                                             Instant cursorTimestamp, UUID cursorId, int pageSize,
                                             String sort) {
        validateDateRange(from, to);
        validateRuleViolated(ruleViolated);
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        if ("asc".equals(sort)) {
            return assessmentRepository.findFlaggedAsc(customerId, ruleViolated, minRiskScore, maxRiskScore,
                    from, to, cursorTimestamp, cursorId, PageRequest.of(0, size));
        }
        return assessmentRepository.findFlagged(customerId, ruleViolated, minRiskScore, maxRiskScore,
                from, to, cursorTimestamp, cursorId, PageRequest.of(0, size));
    }

    @Transactional(readOnly = true)
    public Slice<FraudAssessment> getPendingReview(String customerId, String ruleViolated,
                                                   Integer minRiskScore, Integer maxRiskScore,
                                                   Instant from, Instant to,
                                                   Instant cursorTimestamp, UUID cursorId, int pageSize,
                                                   String sort) {
        validateDateRange(from, to);
        validateRuleViolated(ruleViolated);
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        if ("asc".equals(sort)) {
            return assessmentRepository.findPendingReviewAsc(customerId, ruleViolated, minRiskScore, maxRiskScore,
                    from, to, cursorTimestamp, cursorId, PageRequest.of(0, size));
        }
        return assessmentRepository.findPendingReview(customerId, ruleViolated, minRiskScore, maxRiskScore,
                from, to, cursorTimestamp, cursorId, PageRequest.of(0, size));
    }

    @Transactional(readOnly = true)
    public Slice<FraudAssessment> getPassed(String customerId, Integer minRiskScore,
                                            Instant from, Instant to,
                                            Instant cursorTimestamp, UUID cursorId, int pageSize,
                                            String sort) {
        validateDateRange(from, to);
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        if ("asc".equals(sort)) {
            return assessmentRepository.findPassedAsc(customerId, minRiskScore, from, to, cursorTimestamp, cursorId,
                    PageRequest.of(0, size));
        }
        return assessmentRepository.findPassed(customerId, minRiskScore, from, to, cursorTimestamp, cursorId,
                PageRequest.of(0, size));
    }

    @Transactional(readOnly = true)
    public Slice<FraudAssessment> getFlaggedByMerchant(String merchantId, String ruleViolated,
                                                        Integer minRiskScore,
                                                        Instant from, Instant to,
                                                        Instant cursorTimestamp, UUID cursorId, int pageSize,
                                                        String sort) {
        validateDateRange(from, to);
        validateRuleViolated(ruleViolated);
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        if ("asc".equals(sort)) {
            return assessmentRepository.findFlaggedByMerchantAsc(merchantId, ruleViolated, minRiskScore,
                    from, to, cursorTimestamp, cursorId, PageRequest.of(0, size));
        }
        return assessmentRepository.findFlaggedByMerchant(merchantId, ruleViolated, minRiskScore,
                from, to, cursorTimestamp, cursorId, PageRequest.of(0, size));
    }

    @Transactional(readOnly = true)
    public FraudSummaryDto getFraudSummary(Instant from, Instant to) {
        validateDateRange(from, to);
        long totalAssessed = assessmentRepository.countInRange(from, to);
        long totalFlagged = assessmentRepository.countFlaggedInRange(from, to);
        long totalPassed = totalAssessed - totalFlagged;
        double fraudRate = totalAssessed > 0
                ? Math.round((totalFlagged * 100.0 / totalAssessed) * 100.0) / 100.0
                : 0.0;

        List<Object[]> ruleRows = assessmentRepository.countByRuleInRange(from, to);
        List<RuleBreakdownDto> ruleBreakdown = ruleRows.stream().map(row -> {
            String ruleName = (String) row[0];
            long count = ((Number) row[1]).longValue();
            double pct = totalFlagged > 0
                    ? Math.round((count * 100.0 / totalFlagged) * 100.0) / 100.0
                    : 0.0;
            return new RuleBreakdownDto(ruleName, count, pct);
        }).toList();

        FraudSummaryDto dto = new FraudSummaryDto();
        dto.setFrom(from);
        dto.setTo(to);
        dto.setTotalAssessed(totalAssessed);
        dto.setTotalFlagged(totalFlagged);
        dto.setTotalPassed(totalPassed);
        dto.setFraudRate(fraudRate);
        dto.setRuleBreakdown(ruleBreakdown);
        return dto;
    }

    @Transactional(readOnly = true)
    public CustomerRiskSummaryDto getCustomerRiskSummary(String customerId, Instant since) {
        long total = transactionRepository.countByCustomerId(customerId, since);
        long flagged = assessmentRepository.countFlaggedByCustomerId(customerId, since);
        long passed = total - flagged;
        double fraudRate = total > 0
                ? Math.round((flagged * 100.0 / total) * 100.0) / 100.0
                : 0.0;

        Integer maxScore = assessmentRepository.findMaxRiskScoreByCustomerId(customerId, since);

        List<Object[]> ruleRows = assessmentRepository.findTopRulesByCustomerId(
                customerId, since, PageRequest.of(0, 3));
        List<String> topRules = ruleRows.stream().map(row -> (String) row[0]).toList();

        Instant first = transactionRepository.findFirstTransactionTimestamp(customerId).orElse(null);
        Instant last = transactionRepository.findLastTransactionTimestamp(customerId).orElse(null);

        CustomerRiskSummaryDto dto = new CustomerRiskSummaryDto();
        dto.setCustomerId(customerId);
        dto.setTotalTransactions(total);
        dto.setFlaggedCount(flagged);
        dto.setPassedCount(passed);
        dto.setFraudRate(fraudRate);
        dto.setHighestRiskScore(maxScore != null ? maxScore : 0);
        dto.setMostTriggeredRules(topRules);
        dto.setFirstTransactionAt(first);
        dto.setLastTransactionAt(last);
        return dto;
    }

    @Transactional(readOnly = true)
    public MerchantRiskSummaryDto getMerchantRiskSummary(String merchantId, Instant since) {
        long total = transactionRepository.countByMerchantId(merchantId, since);
        long flagged = assessmentRepository.countFlaggedByMerchantId(merchantId, since);
        long passed = total - flagged;
        double fraudRate = total > 0
                ? Math.round((flagged * 100.0 / total) * 100.0) / 100.0
                : 0.0;

        Integer maxScore = assessmentRepository.findMaxRiskScoreByMerchantId(merchantId, since);

        List<Object[]> ruleRows = assessmentRepository.findTopRulesByMerchantId(
                merchantId, since, PageRequest.of(0, 3));
        List<String> topRules = ruleRows.stream().map(row -> (String) row[0]).toList();

        long uniqueCustomers = transactionRepository.countDistinctCustomersByMerchantId(merchantId);
        Instant first = transactionRepository.findFirstTransactionTimestampByMerchantId(merchantId).orElse(null);
        Instant last = transactionRepository.findLastTransactionTimestampByMerchantId(merchantId).orElse(null);

        MerchantRiskSummaryDto dto = new MerchantRiskSummaryDto();
        dto.setMerchantId(merchantId);
        dto.setTotalTransactions(total);
        dto.setFlaggedCount(flagged);
        dto.setPassedCount(passed);
        dto.setFraudRate(fraudRate);
        dto.setHighestRiskScore(maxScore != null ? maxScore : 0);
        dto.setUniqueCustomers(uniqueCustomers);
        dto.setMostTriggeredRules(topRules);
        dto.setFirstTransactionAt(first);
        dto.setLastTransactionAt(last);
        return dto;
    }
}
