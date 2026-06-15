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

    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            WHERE fa.fraudulent = true
              AND (:cursor IS NULL OR fa.assessedAt < :cursor)
            ORDER BY fa.assessedAt DESC
            """)
    Slice<FraudAssessment> findFlaggedBefore(@Param("cursor") Instant cursor, Pageable pageable);

    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            WHERE fa.fraudulent = true
              AND t.customerId = :customerId
              AND (:cursor IS NULL OR fa.assessedAt < :cursor)
            ORDER BY fa.assessedAt DESC
            """)
    Slice<FraudAssessment> findFlaggedByCustomerBefore(@Param("customerId") String customerId,
                                                       @Param("cursor") Instant cursor,
                                                       Pageable pageable);

    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            JOIN fa.ruleViolations rv
            WHERE fa.fraudulent = true
              AND rv.ruleName = :ruleName
              AND (:cursor IS NULL OR fa.assessedAt < :cursor)
            ORDER BY fa.assessedAt DESC
            """)
    Slice<FraudAssessment> findFlaggedByRuleBefore(@Param("ruleName") String ruleName,
                                                   @Param("cursor") Instant cursor,
                                                   Pageable pageable);

    @Query("""
            SELECT fa FROM FraudAssessment fa
            JOIN FETCH fa.transaction t
            WHERE fa.fraudulent = true
              AND fa.riskScore >= :minRiskScore
              AND (:cursor IS NULL OR fa.assessedAt < :cursor)
            ORDER BY fa.assessedAt DESC
            """)
    Slice<FraudAssessment> findFlaggedByMinRiskScoreBefore(@Param("minRiskScore") int minRiskScore,
                                                           @Param("cursor") Instant cursor,
                                                           Pageable pageable);
}
