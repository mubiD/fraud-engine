package com.fraudengine.repository;

import com.fraudengine.model.Transaction;
import com.fraudengine.model.TransactionId;
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
public interface TransactionRepository extends JpaRepository<Transaction, TransactionId> {

    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdOnly(@Param("id") UUID id);

    // Query-API variant of findByIdOnly: eagerly fetches the assessment + its rule violations
    // so TransactionMapper can map to a DTO after this method's @Transactional scope closes
    // (open-in-view is disabled) without hitting LazyInitializationException. Not used on the
    // Kafka consumer's hot path, which only needs existence/id and doesn't map to a DTO.
    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN FETCH t.assessment a
            LEFT JOIN FETCH a.ruleViolations
            WHERE t.id = :id
            """)
    Optional<Transaction> findByIdWithAssessment(@Param("id") UUID id);

    @Query("""
            SELECT t FROM Transaction t
            WHERE t.customerId = :customerId
              AND t.timestamp >= :since
            ORDER BY t.timestamp DESC
            """)
    List<Transaction> findRecentByCustomer(@Param("customerId") String customerId,
                                           @Param("since") Instant since);

    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN FETCH t.assessment a
            LEFT JOIN FETCH a.ruleViolations
            WHERE t.customerId = :customerId
              AND (CAST(:cursorTimestamp AS timestamp) IS NULL
                   OR t.timestamp < :cursorTimestamp
                   OR (t.timestamp = :cursorTimestamp AND t.id < :cursorId))
              AND (CAST(:from AS timestamp) IS NULL OR t.timestamp >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR t.timestamp <= :to)
            ORDER BY t.timestamp DESC, t.id DESC
            """)
    Slice<Transaction> findByCustomerInRange(@Param("customerId") String customerId,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to,
                                             @Param("cursorTimestamp") Instant cursorTimestamp,
                                             @Param("cursorId") UUID cursorId,
                                             Pageable pageable);

    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN FETCH t.assessment a
            LEFT JOIN FETCH a.ruleViolations
            WHERE t.customerId = :customerId
              AND (CAST(:cursorTimestamp AS timestamp) IS NULL
                   OR t.timestamp > :cursorTimestamp
                   OR (t.timestamp = :cursorTimestamp AND t.id > :cursorId))
              AND (CAST(:from AS timestamp) IS NULL OR t.timestamp >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR t.timestamp <= :to)
            ORDER BY t.timestamp ASC, t.id ASC
            """)
    Slice<Transaction> findByCustomerInRangeAsc(@Param("customerId") String customerId,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to,
                                                @Param("cursorTimestamp") Instant cursorTimestamp,
                                                @Param("cursorId") UUID cursorId,
                                                Pageable pageable);

    // Scoped to a single currency so a customer transacting in more than one currency
    // never has those amounts pooled as equivalent magnitude by CumulativeSpendingRule's
    // daily-spend check, mirroring the Kafka Streams path's per-currency
    // CustomerActivityState.dailySpendTotal(asOf, currency).
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM Transaction t
            WHERE t.customerId = :customerId
              AND t.timestamp >= :since
              AND t.id != :transactionId
              AND t.currency = :currency
            """)
    java.math.BigDecimal sumAmountByCustomerSince(@Param("customerId") String customerId,
                                                  @Param("since") Instant since,
                                                  @Param("transactionId") UUID transactionId,
                                                  @Param("currency") String currency);

    @Query("""
            SELECT COUNT(t) FROM Transaction t
            WHERE t.customerId = :customerId
              AND (CAST(:since AS timestamp) IS NULL OR t.timestamp >= :since)
            """)
    long countByCustomerId(@Param("customerId") String customerId, @Param("since") Instant since);

    @Query("SELECT MIN(t.timestamp) FROM Transaction t WHERE t.customerId = :customerId")
    Optional<Instant> findFirstTransactionTimestamp(@Param("customerId") String customerId);

    @Query("SELECT MAX(t.timestamp) FROM Transaction t WHERE t.customerId = :customerId")
    Optional<Instant> findLastTransactionTimestamp(@Param("customerId") String customerId);

    @Query("""
            SELECT COUNT(t) FROM Transaction t
            WHERE t.merchantId = :merchantId
              AND (CAST(:since AS timestamp) IS NULL OR t.timestamp >= :since)
            """)
    long countByMerchantId(@Param("merchantId") String merchantId, @Param("since") Instant since);

    @Query("SELECT COUNT(DISTINCT t.customerId) FROM Transaction t WHERE t.merchantId = :merchantId")
    long countDistinctCustomersByMerchantId(@Param("merchantId") String merchantId);

    @Query("SELECT MIN(t.timestamp) FROM Transaction t WHERE t.merchantId = :merchantId")
    Optional<Instant> findFirstTransactionTimestampByMerchantId(@Param("merchantId") String merchantId);

    @Query("SELECT MAX(t.timestamp) FROM Transaction t WHERE t.merchantId = :merchantId")
    Optional<Instant> findLastTransactionTimestampByMerchantId(@Param("merchantId") String merchantId);
}
