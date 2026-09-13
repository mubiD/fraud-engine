package com.fraudengine.service;

import com.fraudengine.api.dto.CustomerRiskSummaryDto;
import com.fraudengine.api.dto.FraudSummaryDto;
import com.fraudengine.api.dto.MerchantRiskSummaryDto;
import com.fraudengine.api.dto.RuleBreakdownDto;
import com.fraudengine.api.dto.SortDirection;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.repository.FraudAssessmentRepository;
import com.fraudengine.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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

    // The keyset-cursor comparison direction (< vs >) is threaded to the repository as an
    // explicit boolean since it can't be derived from Sort; the ORDER BY itself is derived
    // here and appended by Spring Data, since the repositories' @Query methods intentionally
    // omit their own ORDER BY.
    private Pageable pageable(int size, SortDirection sort, String... properties) {
        Sort.Direction direction = sort == SortDirection.asc ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(0, size, Sort.by(direction, properties));
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
                                              SortDirection sort) {
        validateDateRange(from, to);
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        return transactionRepository.findByCustomerInRange(customerId, from, to, cursorTimestamp, cursorId,
                sort == SortDirection.asc, pageable(size, sort, "timestamp", "id"));
    }

    @Transactional(readOnly = true)
    public Optional<Transaction> getById(UUID transactionId) {
        return transactionRepository.findByIdWithAssessment(transactionId);
    }

    @Transactional(readOnly = true)
    public Optional<FraudAssessment> getAssessment(UUID transactionId) {
        return assessmentRepository.findByTransactionIdWithDetails(transactionId);
    }

    @Transactional(readOnly = true)
    public Slice<FraudAssessment> getFlagged(String customerId, String ruleViolated,
                                             Integer minRiskScore, Integer maxRiskScore,
                                             Instant from, Instant to,
                                             Instant cursorTimestamp, UUID cursorId, int pageSize,
                                             SortDirection sort) {
        validateDateRange(from, to);
        validateRuleViolated(ruleViolated);
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        return assessmentRepository.findFlagged(customerId, ruleViolated, minRiskScore, maxRiskScore,
                from, to, cursorTimestamp, cursorId, sort == SortDirection.asc,
                pageable(size, sort, "assessedAt", "id"));
    }

    @Transactional(readOnly = true)
    public Slice<FraudAssessment> getPendingReview(String customerId, String ruleViolated,
                                                   Integer minRiskScore, Integer maxRiskScore,
                                                   Instant from, Instant to,
                                                   Instant cursorTimestamp, UUID cursorId, int pageSize,
                                                   SortDirection sort) {
        validateDateRange(from, to);
        validateRuleViolated(ruleViolated);
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        return assessmentRepository.findPendingReview(customerId, ruleViolated, minRiskScore, maxRiskScore,
                from, to, cursorTimestamp, cursorId, sort == SortDirection.asc,
                pageable(size, sort, "assessedAt", "id"));
    }

    @Transactional(readOnly = true)
    public Slice<FraudAssessment> getPassed(String customerId, Integer minRiskScore,
                                            Instant from, Instant to,
                                            Instant cursorTimestamp, UUID cursorId, int pageSize,
                                            SortDirection sort) {
        validateDateRange(from, to);
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        return assessmentRepository.findPassed(customerId, minRiskScore, from, to, cursorTimestamp, cursorId,
                sort == SortDirection.asc, pageable(size, sort, "assessedAt", "id"));
    }

    @Transactional(readOnly = true)
    public Slice<FraudAssessment> getFlaggedByMerchant(String merchantId, String ruleViolated,
                                                        Integer minRiskScore,
                                                        Instant from, Instant to,
                                                        Instant cursorTimestamp, UUID cursorId, int pageSize,
                                                        SortDirection sort) {
        validateDateRange(from, to);
        validateRuleViolated(ruleViolated);
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        return assessmentRepository.findFlaggedByMerchant(merchantId, ruleViolated, minRiskScore,
                from, to, cursorTimestamp, cursorId, sort == SortDirection.asc,
                pageable(size, sort, "assessedAt", "id"));
    }

    // REPEATABLE_READ (Postgres: snapshot isolation) so the independent COUNTs below all see
    // the same snapshot — otherwise a row committed between them under the default READ
    // COMMITTED can make totalFlagged momentarily exceed totalAssessed. Safe for a read-only
    // transaction: no serialization-failure/retry risk, which only applies to writers.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FraudSummaryDto getFraudSummary(Instant from, Instant to) {
        validateDateRange(from, to);
        long totalAssessed = assessmentRepository.countInRange(from, to);
        long totalFlagged = assessmentRepository.countFlaggedInRange(from, to);
        long totalNotFlagged = totalAssessed - totalFlagged;
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
        dto.setTotalNotFlagged(totalNotFlagged);
        dto.setFraudRate(fraudRate);
        dto.setRuleBreakdown(ruleBreakdown);
        return dto;
    }

    // See getFraudSummary for why REPEATABLE_READ: same read-skew risk across this method's
    // several independent count/aggregate queries.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
        dto.setNotFlaggedCount(passed);
        dto.setFraudRate(fraudRate);
        dto.setHighestRiskScore(maxScore != null ? maxScore : 0);
        dto.setMostTriggeredRules(topRules);
        dto.setFirstTransactionAt(first);
        dto.setLastTransactionAt(last);
        return dto;
    }

    // See getFraudSummary for why REPEATABLE_READ: same read-skew risk across this method's
    // several independent count/aggregate queries.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
        dto.setNotFlaggedCount(passed);
        dto.setFraudRate(fraudRate);
        dto.setHighestRiskScore(maxScore != null ? maxScore : 0);
        dto.setUniqueCustomers(uniqueCustomers);
        dto.setMostTriggeredRules(topRules);
        dto.setFirstTransactionAt(first);
        dto.setLastTransactionAt(last);
        return dto;
    }
}
