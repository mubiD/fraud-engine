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
              AND (:cursor IS NULL OR t.timestamp < :cursor)
            ORDER BY t.timestamp DESC
            """)
    Slice<Transaction> findByCustomerIdBefore(@Param("customerId") String customerId,
                                              @Param("cursor") Instant cursor,
                                              Pageable pageable);
}
