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
            WHERE t.merchantId   = :merchantId
              AND t.customerId   = :customerId
              AND t.amount       = :amount
              AND t.timestamp   >= :since
            """)
    List<Transaction> findDuplicateCandidates(@Param("merchantId") String merchantId,
                                              @Param("customerId") String customerId,
                                              @Param("amount") java.math.BigDecimal amount,
                                              @Param("since") Instant since);

    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN FETCH t.assessment a
            WHERE t.customerId = :customerId
              AND (:cursorTimestamp IS NULL
                   OR t.timestamp < :cursorTimestamp
                   OR (t.timestamp = :cursorTimestamp AND t.id < :cursorId))
              AND (:from IS NULL OR t.timestamp >= :from)
              AND (:to IS NULL OR t.timestamp <= :to)
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
            WHERE t.customerId = :customerId
              AND (:cursorTimestamp IS NULL
                   OR t.timestamp > :cursorTimestamp
                   OR (t.timestamp = :cursorTimestamp AND t.id > :cursorId))
              AND (:from IS NULL OR t.timestamp >= :from)
              AND (:to IS NULL OR t.timestamp <= :to)
            ORDER BY t.timestamp ASC, t.id ASC
            """)
    Slice<Transaction> findByCustomerInRangeAsc(@Param("customerId") String customerId,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to,
                                                @Param("cursorTimestamp") Instant cursorTimestamp,
                                                @Param("cursorId") UUID cursorId,
                                                Pageable pageable);

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM Transaction t
            WHERE t.customerId = :customerId
              AND t.timestamp >= :since
            """)
    java.math.BigDecimal sumAmountByCustomerSince(@Param("customerId") String customerId,
                                                  @Param("since") Instant since);

    @Query("""
            SELECT COUNT(t) FROM Transaction t
            WHERE t.customerId = :customerId
              AND (:since IS NULL OR t.timestamp >= :since)
            """)
    long countByCustomerId(@Param("customerId") String customerId, @Param("since") Instant since);

    @Query("SELECT MIN(t.timestamp) FROM Transaction t WHERE t.customerId = :customerId")
    Optional<Instant> findFirstTransactionTimestamp(@Param("customerId") String customerId);

    @Query("SELECT MAX(t.timestamp) FROM Transaction t WHERE t.customerId = :customerId")
    Optional<Instant> findLastTransactionTimestamp(@Param("customerId") String customerId);

    @Query("""
            SELECT COUNT(t) FROM Transaction t
            WHERE t.merchantId = :merchantId
              AND (:since IS NULL OR t.timestamp >= :since)
            """)
    long countByMerchantId(@Param("merchantId") String merchantId, @Param("since") Instant since);

    @Query("SELECT COUNT(DISTINCT t.customerId) FROM Transaction t WHERE t.merchantId = :merchantId")
    long countDistinctCustomersByMerchantId(@Param("merchantId") String merchantId);

    @Query("SELECT MIN(t.timestamp) FROM Transaction t WHERE t.merchantId = :merchantId")
    Optional<Instant> findFirstTransactionTimestampByMerchantId(@Param("merchantId") String merchantId);

    @Query("SELECT MAX(t.timestamp) FROM Transaction t WHERE t.merchantId = :merchantId")
    Optional<Instant> findLastTransactionTimestampByMerchantId(@Param("merchantId") String merchantId);
}
