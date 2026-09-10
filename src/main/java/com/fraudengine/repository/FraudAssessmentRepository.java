package com.fraudengine.repository;

import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.enums.AssessmentOutcome;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // Atomic, conditional resolution used by AssessmentOutcomeService instead of a
    // read-then-save check-then-act: the WHERE clause re-verifies outcome = UNRESOLVED at
    // the database, at write time, so two concurrent PATCHes on the same assessment can no
    // longer both pass a stale in-memory check and last-write-win each other. Returns the
    // number of rows updated (0 or 1); 0 means someone else resolved it first.
    // clearAutomatically = true: this bulk update bypasses the persistence context, so any
    // already-loaded FraudAssessment instance must not be relied on as still representing
    // the DB row past this call.
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE FraudAssessment fa SET fa.outcome = :newOutcome
            WHERE fa.id = :id AND fa.outcome = com.fraudengine.model.enums.AssessmentOutcome.UNRESOLVED
            """)
    int resolveOutcomeIfUnresolved(@Param("id") UUID id, @Param("newOutcome") AssessmentOutcome newOutcome);

    // Query-API variant of findByTransactionId: eagerly fetches the transaction + rule
    // violations so TransactionMapper can map to a DTO after this method's @Transactional
    // scope closes (open-in-view is disabled) without hitting LazyInitializationException.
    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            LEFT JOIN FETCH fa.ruleViolations
            WHERE t.id = :transactionId
            """)
    Optional<FraudAssessment> findByTransactionIdWithDetails(@Param("transactionId") UUID transactionId);

    // -----------------------------------------------------------------------
    // Flagged queries
    // -----------------------------------------------------------------------

    // LEFT JOIN FETCH on ruleViolations (a to-many collection) combined with Pageable means
    // Hibernate falls back to in-memory pagination for this query (can't LIMIT at the SQL
    // level with a to-many fetch join), which is acceptable at this project's scale (pageSize capped
    // at 1000). The fetch join is needed so TransactionMapper can map ruleViolations to a DTO
    // after this method's @Transactional scope closes (open-in-view is disabled).
    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            LEFT JOIN FETCH fa.ruleViolations
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND (CAST(:cursorTimestamp AS timestamp) IS NULL
                   OR fa.assessedAt < :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id < :cursorId))
              AND (CAST(:from AS timestamp) IS NULL OR fa.assessedAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR fa.assessedAt <= :to)
              AND (CAST(:customerId AS string) IS NULL OR t.customerId = :customerId)
              AND (CAST(:minRiskScore AS integer) IS NULL OR fa.riskScore >= :minRiskScore)
              AND (CAST(:maxRiskScore AS integer) IS NULL OR fa.riskScore <= :maxRiskScore)
              AND (CAST(:ruleViolated AS string) IS NULL OR EXISTS (
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
            LEFT JOIN FETCH fa.ruleViolations
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND t.merchantId = :merchantId
              AND (CAST(:cursorTimestamp AS timestamp) IS NULL
                   OR fa.assessedAt < :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id < :cursorId))
              AND (CAST(:from AS timestamp) IS NULL OR fa.assessedAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR fa.assessedAt <= :to)
              AND (CAST(:minRiskScore AS integer) IS NULL OR fa.riskScore >= :minRiskScore)
              AND (CAST(:ruleViolated AS string) IS NULL OR EXISTS (
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
            LEFT JOIN FETCH fa.ruleViolations
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND (CAST(:cursorTimestamp AS timestamp) IS NULL
                   OR fa.assessedAt > :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id > :cursorId))
              AND (CAST(:from AS timestamp) IS NULL OR fa.assessedAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR fa.assessedAt <= :to)
              AND (CAST(:customerId AS string) IS NULL OR t.customerId = :customerId)
              AND (CAST(:minRiskScore AS integer) IS NULL OR fa.riskScore >= :minRiskScore)
              AND (CAST(:maxRiskScore AS integer) IS NULL OR fa.riskScore <= :maxRiskScore)
              AND (CAST(:ruleViolated AS string) IS NULL OR EXISTS (
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
            LEFT JOIN FETCH fa.ruleViolations
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND t.merchantId = :merchantId
              AND (CAST(:cursorTimestamp AS timestamp) IS NULL
                   OR fa.assessedAt > :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id > :cursorId))
              AND (CAST(:from AS timestamp) IS NULL OR fa.assessedAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR fa.assessedAt <= :to)
              AND (CAST(:minRiskScore AS integer) IS NULL OR fa.riskScore >= :minRiskScore)
              AND (CAST(:ruleViolated AS string) IS NULL OR EXISTS (
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
            LEFT JOIN FETCH fa.ruleViolations
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.PENDING_REVIEW
              AND (CAST(:cursorTimestamp AS timestamp) IS NULL
                   OR fa.assessedAt < :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id < :cursorId))
              AND (CAST(:from AS timestamp) IS NULL OR fa.assessedAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR fa.assessedAt <= :to)
              AND (CAST(:customerId AS string) IS NULL OR t.customerId = :customerId)
              AND (CAST(:minRiskScore AS integer) IS NULL OR fa.riskScore >= :minRiskScore)
              AND (CAST(:maxRiskScore AS integer) IS NULL OR fa.riskScore <= :maxRiskScore)
              AND (CAST(:ruleViolated AS string) IS NULL OR EXISTS (
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
            LEFT JOIN FETCH fa.ruleViolations
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.PENDING_REVIEW
              AND (CAST(:cursorTimestamp AS timestamp) IS NULL
                   OR fa.assessedAt > :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id > :cursorId))
              AND (CAST(:from AS timestamp) IS NULL OR fa.assessedAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR fa.assessedAt <= :to)
              AND (CAST(:customerId AS string) IS NULL OR t.customerId = :customerId)
              AND (CAST(:minRiskScore AS integer) IS NULL OR fa.riskScore >= :minRiskScore)
              AND (CAST(:maxRiskScore AS integer) IS NULL OR fa.riskScore <= :maxRiskScore)
              AND (CAST(:ruleViolated AS string) IS NULL OR EXISTS (
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
            LEFT JOIN FETCH fa.ruleViolations
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.CLEARED
              AND (CAST(:cursorTimestamp AS timestamp) IS NULL
                   OR fa.assessedAt < :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id < :cursorId))
              AND (CAST(:from AS timestamp) IS NULL OR fa.assessedAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR fa.assessedAt <= :to)
              AND (CAST(:customerId AS string) IS NULL OR t.customerId = :customerId)
              AND (CAST(:minRiskScore AS integer) IS NULL OR fa.riskScore >= :minRiskScore)
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
            LEFT JOIN FETCH fa.ruleViolations
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.CLEARED
              AND (CAST(:cursorTimestamp AS timestamp) IS NULL
                   OR fa.assessedAt > :cursorTimestamp
                   OR (fa.assessedAt = :cursorTimestamp AND fa.id > :cursorId))
              AND (CAST(:from AS timestamp) IS NULL OR fa.assessedAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR fa.assessedAt <= :to)
              AND (CAST(:customerId AS string) IS NULL OR t.customerId = :customerId)
              AND (CAST(:minRiskScore AS integer) IS NULL OR fa.riskScore >= :minRiskScore)
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
            WHERE (CAST(:from AS timestamp) IS NULL OR fa.assessedAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR fa.assessedAt <= :to)
            """)
    long countInRange(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            SELECT COUNT(fa) FROM FraudAssessment fa
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND (CAST(:from AS timestamp) IS NULL OR fa.assessedAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR fa.assessedAt <= :to)
            """)
    long countFlaggedInRange(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            SELECT rv.ruleName, COUNT(DISTINCT fa.id)
            FROM FraudAssessment fa
            JOIN fa.ruleViolations rv
            WHERE fa.disposition = com.fraudengine.model.enums.Disposition.FLAGGED
              AND (CAST(:from AS timestamp) IS NULL OR fa.assessedAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR fa.assessedAt <= :to)
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
              AND (CAST(:since AS timestamp) IS NULL OR fa.assessedAt >= :since)
            """)
    long countFlaggedByCustomerId(@Param("customerId") String customerId, @Param("since") Instant since);

    @Query("""
            SELECT MAX(fa.riskScore) FROM FraudAssessment fa
            JOIN fa.transaction t
            WHERE t.customerId = :customerId
              AND (CAST(:since AS timestamp) IS NULL OR fa.assessedAt >= :since)
            """)
    Integer findMaxRiskScoreByCustomerId(@Param("customerId") String customerId, @Param("since") Instant since);

    @Query("""
            SELECT rv.ruleName, COUNT(rv)
            FROM FraudAssessment fa
            JOIN fa.ruleViolations rv
            JOIN fa.transaction t
            WHERE t.customerId = :customerId
              AND (CAST(:since AS timestamp) IS NULL OR fa.assessedAt >= :since)
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
              AND (CAST(:since AS timestamp) IS NULL OR fa.assessedAt >= :since)
            """)
    long countFlaggedByMerchantId(@Param("merchantId") String merchantId, @Param("since") Instant since);

    @Query("""
            SELECT MAX(fa.riskScore) FROM FraudAssessment fa
            JOIN fa.transaction t
            WHERE t.merchantId = :merchantId
              AND (CAST(:since AS timestamp) IS NULL OR fa.assessedAt >= :since)
            """)
    Integer findMaxRiskScoreByMerchantId(@Param("merchantId") String merchantId, @Param("since") Instant since);

    @Query("""
            SELECT rv.ruleName, COUNT(rv)
            FROM FraudAssessment fa
            JOIN fa.ruleViolations rv
            JOIN fa.transaction t
            WHERE t.merchantId = :merchantId
              AND (CAST(:since AS timestamp) IS NULL OR fa.assessedAt >= :since)
            GROUP BY rv.ruleName
            ORDER BY COUNT(rv) DESC
            """)
    List<Object[]> findTopRulesByMerchantId(@Param("merchantId") String merchantId,
                                            @Param("since") Instant since,
                                            Pageable pageable);
}
