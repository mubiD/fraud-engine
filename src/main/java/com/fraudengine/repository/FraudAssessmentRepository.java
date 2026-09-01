package com.fraudengine.repository;

import com.fraudengine.model.FraudAssessment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FraudAssessmentRepository extends JpaRepository<FraudAssessment, UUID> {

    Optional<FraudAssessment> findByTransactionId(UUID transactionId);

    // -----------------------------------------------------------------------
    // Flagged queries
    // -----------------------------------------------------------------------

    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND (:cursorTimestamp IS NULL
                   OR fa.assessedAt < :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id < :cursorId))
              AND (:from IS NULL OR fa.assessedAt >= :from)
              AND (:to IS NULL OR fa.assessedAt <= :to)
              AND (:customerId IS NULL OR t.customerId = :customerId)
              AND (:minRiskScore IS NULL OR fa.riskScore >= :minRiskScore)
              AND (:maxRiskScore IS NULL OR fa.riskScore <= :maxRiskScore)
              AND (:ruleViolated IS NULL OR EXISTS (
                  SELECT rv FROM RuleViolation rv
                  WHERE rv.assessment = fa AND rv.ruleName = :ruleViolated
              ))
            ORDER BY fa.assessedAt DESC, fa.id DESC
            """)
    Slice<FraudAssessment> findFlagged(@Param("customerId") String customerId,
                                       @Param("ruleViolated") String ruleViolated,
                                       @Param("minRiskScore") Integer minRiskScore,
                                       @Param("maxRiskScore") Integer maxRiskScore,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to,
                                       @Param("cursorTimestamp") Instant cursorTimestamp,
                                       @Param("cursorId") UUID cursorId,
                                       Pageable pageable);

    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND t.merchantId = :merchantId
              AND (:cursorTimestamp IS NULL
                   OR fa.assessedAt < :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id < :cursorId))
              AND (:from IS NULL OR fa.assessedAt >= :from)
              AND (:to IS NULL OR fa.assessedAt <= :to)
              AND (:minRiskScore IS NULL OR fa.riskScore >= :minRiskScore)
              AND (:ruleViolated IS NULL OR EXISTS (
                  SELECT rv FROM RuleViolation rv
                  WHERE rv.assessment = fa AND rv.ruleName = :ruleViolated
              ))
            ORDER BY fa.assessedAt DESC, fa.id DESC
            """)
    Slice<FraudAssessment> findFlaggedByMerchant(@Param("merchantId") String merchantId,
                                                  @Param("ruleViolated") String ruleViolated,
                                                  @Param("minRiskScore") Integer minRiskScore,
                                                  @Param("from") Instant from,
                                                  @Param("to") Instant to,
                                                  @Param("cursorTimestamp") Instant cursorTimestamp,
                                                  @Param("cursorId") UUID cursorId,
                                                  Pageable pageable);

    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND (:cursorTimestamp IS NULL
                   OR fa.assessedAt > :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id > :cursorId))
              AND (:from IS NULL OR fa.assessedAt >= :from)
              AND (:to IS NULL OR fa.assessedAt <= :to)
              AND (:customerId IS NULL OR t.customerId = :customerId)
              AND (:minRiskScore IS NULL OR fa.riskScore >= :minRiskScore)
              AND (:maxRiskScore IS NULL OR fa.riskScore <= :maxRiskScore)
              AND (:ruleViolated IS NULL OR EXISTS (
                  SELECT rv FROM RuleViolation rv
                  WHERE rv.assessment = fa AND rv.ruleName = :ruleViolated
              ))
            ORDER BY fa.assessedAt ASC, fa.id ASC
            """)
    Slice<FraudAssessment> findFlaggedAsc(@Param("customerId") String customerId,
                                          @Param("ruleViolated") String ruleViolated,
                                          @Param("minRiskScore") Integer minRiskScore,
                                          @Param("maxRiskScore") Integer maxRiskScore,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to,
                                          @Param("cursorTimestamp") Instant cursorTimestamp,
                                          @Param("cursorId") UUID cursorId,
                                          Pageable pageable);

    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND t.merchantId = :merchantId
              AND (:cursorTimestamp IS NULL
                   OR fa.assessedAt > :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id > :cursorId))
              AND (:from IS NULL OR fa.assessedAt >= :from)
              AND (:to IS NULL OR fa.assessedAt <= :to)
              AND (:minRiskScore IS NULL OR fa.riskScore >= :minRiskScore)
              AND (:ruleViolated IS NULL OR EXISTS (
                  SELECT rv FROM RuleViolation rv
                  WHERE rv.assessment = fa AND rv.ruleName = :ruleViolated
              ))
            ORDER BY fa.assessedAt ASC, fa.id ASC
            """)
    Slice<FraudAssessment> findFlaggedByMerchantAsc(@Param("merchantId") String merchantId,
                                                     @Param("ruleViolated") String ruleViolated,
                                                     @Param("minRiskScore") Integer minRiskScore,
                                                     @Param("from") Instant from,
                                                     @Param("to") Instant to,
                                                     @Param("cursorTimestamp") Instant cursorTimestamp,
                                                     @Param("cursorId") UUID cursorId,
                                                     Pageable pageable);

    // -----------------------------------------------------------------------
    // Pending-review queries
    // -----------------------------------------------------------------------

    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.PENDING_REVIEW
              AND (:cursorTimestamp IS NULL
                   OR fa.assessedAt < :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id < :cursorId))
              AND (:from IS NULL OR fa.assessedAt >= :from)
              AND (:to IS NULL OR fa.assessedAt <= :to)
              AND (:customerId IS NULL OR t.customerId = :customerId)
              AND (:minRiskScore IS NULL OR fa.riskScore >= :minRiskScore)
              AND (:maxRiskScore IS NULL OR fa.riskScore <= :maxRiskScore)
              AND (:ruleViolated IS NULL OR EXISTS (
                  SELECT rv FROM RuleViolation rv
                  WHERE rv.assessment = fa AND rv.ruleName = :ruleViolated
              ))
            ORDER BY fa.assessedAt DESC, fa.id DESC
            """)
    Slice<FraudAssessment> findPendingReview(@Param("customerId") String customerId,
                                             @Param("ruleViolated") String ruleViolated,
                                             @Param("minRiskScore") Integer minRiskScore,
                                             @Param("maxRiskScore") Integer maxRiskScore,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to,
                                             @Param("cursorTimestamp") Instant cursorTimestamp,
                                             @Param("cursorId") UUID cursorId,
                                             Pageable pageable);

    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.PENDING_REVIEW
              AND (:cursorTimestamp IS NULL
                   OR fa.assessedAt > :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id > :cursorId))
              AND (:from IS NULL OR fa.assessedAt >= :from)
              AND (:to IS NULL OR fa.assessedAt <= :to)
              AND (:customerId IS NULL OR t.customerId = :customerId)
              AND (:minRiskScore IS NULL OR fa.riskScore >= :minRiskScore)
              AND (:maxRiskScore IS NULL OR fa.riskScore <= :maxRiskScore)
              AND (:ruleViolated IS NULL OR EXISTS (
                  SELECT rv FROM RuleViolation rv
                  WHERE rv.assessment = fa AND rv.ruleName = :ruleViolated
              ))
            ORDER BY fa.assessedAt ASC, fa.id ASC
            """)
    Slice<FraudAssessment> findPendingReviewAsc(@Param("customerId") String customerId,
                                                @Param("ruleViolated") String ruleViolated,
                                                @Param("minRiskScore") Integer minRiskScore,
                                                @Param("maxRiskScore") Integer maxRiskScore,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to,
                                                @Param("cursorTimestamp") Instant cursorTimestamp,
                                                @Param("cursorId") UUID cursorId,
                                                Pageable pageable);

    // -----------------------------------------------------------------------
    // Passed queries
    // -----------------------------------------------------------------------

    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.CLEARED
              AND (:cursorTimestamp IS NULL
                   OR fa.assessedAt < :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id < :cursorId))
              AND (:from IS NULL OR fa.assessedAt >= :from)
              AND (:to IS NULL OR fa.assessedAt <= :to)
              AND (:customerId IS NULL OR t.customerId = :customerId)
              AND (:minRiskScore IS NULL OR fa.riskScore >= :minRiskScore)
            ORDER BY fa.assessedAt DESC, fa.id DESC
            """)
    Slice<FraudAssessment> findPassed(@Param("customerId") String customerId,
                                      @Param("minRiskScore") Integer minRiskScore,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to,
                                      @Param("cursorTimestamp") Instant cursorTimestamp,
                                      @Param("cursorId") UUID cursorId,
                                      Pageable pageable);

    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.CLEARED
              AND (:cursorTimestamp IS NULL
                   OR fa.assessedAt > :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id > :cursorId))
              AND (:from IS NULL OR fa.assessedAt >= :from)
              AND (:to IS NULL OR fa.assessedAt <= :to)
              AND (:customerId IS NULL OR t.customerId = :customerId)
              AND (:minRiskScore IS NULL OR fa.riskScore >= :minRiskScore)
            ORDER BY fa.assessedAt ASC, fa.id ASC
            """)
    Slice<FraudAssessment> findPassedAsc(@Param("customerId") String customerId,
                                         @Param("minRiskScore") Integer minRiskScore,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to,
                                         @Param("cursorTimestamp") Instant cursorTimestamp,
                                         @Param("cursorId") UUID cursorId,
                                         Pageable pageable);

    // -----------------------------------------------------------------------
    // Aggregate queries for fraud summary stats
    // -----------------------------------------------------------------------

    @Query("""
            SELECT COUNT(fa) FROM FraudAssessment fa
            WHERE (:from IS NULL OR fa.assessedAt >= :from)
              AND (:to IS NULL OR fa.assessedAt <= :to)
            """)
    long countInRange(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            SELECT COUNT(fa) FROM FraudAssessment fa
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND (:from IS NULL OR fa.assessedAt >= :from)
              AND (:to IS NULL OR fa.assessedAt <= :to)
            """)
    long countFlaggedInRange(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            SELECT rv.ruleName, COUNT(DISTINCT fa.id)
            FROM FraudAssessment fa
            JOIN fa.ruleViolations rv
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND (:from IS NULL OR fa.assessedAt >= :from)
              AND (:to IS NULL OR fa.assessedAt <= :to)
            GROUP BY rv.ruleName
            ORDER BY COUNT(DISTINCT fa.id) DESC
            """)
    List<Object[]> countByRuleInRange(@Param("from") Instant from, @Param("to") Instant to);

    // -----------------------------------------------------------------------
    // Aggregate queries for customer risk summary
    // -----------------------------------------------------------------------

    @Query("""
            SELECT COUNT(fa) FROM FraudAssessment fa
            JOIN fa.transaction t
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND t.customerId = :customerId
              AND (:since IS NULL OR fa.assessedAt >= :since)
            """)
    long countFlaggedByCustomerId(@Param("customerId") String customerId, @Param("since") Instant since);

    @Query("""
            SELECT MAX(fa.riskScore) FROM FraudAssessment fa
            JOIN fa.transaction t
            WHERE t.customerId = :customerId
              AND (:since IS NULL OR fa.assessedAt >= :since)
            """)
    Integer findMaxRiskScoreByCustomerId(@Param("customerId") String customerId, @Param("since") Instant since);

    @Query("""
            SELECT rv.ruleName, COUNT(rv)
            FROM FraudAssessment fa
            JOIN fa.ruleViolations rv
            JOIN fa.transaction t
            WHERE t.customerId = :customerId
              AND (:since IS NULL OR fa.assessedAt >= :since)
            GROUP BY rv.ruleName
            ORDER BY COUNT(rv) DESC
            """)
    List<Object[]> findTopRulesByCustomerId(@Param("customerId") String customerId,
                                            @Param("since") Instant since,
                                            Pageable pageable);

    // -----------------------------------------------------------------------
    // Aggregate queries for merchant risk summary
    // -----------------------------------------------------------------------

    @Query("""
            SELECT COUNT(fa) FROM FraudAssessment fa
            JOIN fa.transaction t
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND t.merchantId = :merchantId
              AND (:since IS NULL OR fa.assessedAt >= :since)
            """)
    long countFlaggedByMerchantId(@Param("merchantId") String merchantId, @Param("since") Instant since);

    @Query("""
            SELECT MAX(fa.riskScore) FROM FraudAssessment fa
            JOIN fa.transaction t
            WHERE t.merchantId = :merchantId
              AND (:since IS NULL OR fa.assessedAt >= :since)
            """)
    Integer findMaxRiskScoreByMerchantId(@Param("merchantId") String merchantId, @Param("since") Instant since);

    @Query("""
            SELECT rv.ruleName, COUNT(rv)
            FROM FraudAssessment fa
            JOIN fa.ruleViolations rv
            JOIN fa.transaction t
            WHERE t.merchantId = :merchantId
              AND (:since IS NULL OR fa.assessedAt >= :since)
            GROUP BY rv.ruleName
            ORDER BY COUNT(rv) DESC
            """)
    List<Object[]> findTopRulesByMerchantId(@Param("merchantId") String merchantId,
                                            @Param("since") Instant since,
                                            Pageable pageable);
}
