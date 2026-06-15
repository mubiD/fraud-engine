package com.fraudengine.service;

import com.fraudengine.api.dto.SubmitTransactionRequest;
import com.fraudengine.kafka.TransactionEvent;
import com.fraudengine.kafka.TransactionProducer;
import com.fraudengine.model.Transaction;
import com.fraudengine.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final TransactionProducer producer;

    public TransactionService(TransactionRepository transactionRepository,
                               TransactionProducer producer) {
        this.transactionRepository = transactionRepository;
        this.producer = producer;
    }

    @Transactional
    public UUID submit(SubmitTransactionRequest request) {
        Instant timestamp = request.getTimestamp() != null ? request.getTimestamp() : Instant.now();

        Transaction transaction = Transaction.builder()
                .customerId(request.getCustomerId())
                .merchantId(request.getMerchantId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .category(request.getCategory())
                .location(request.getLocation())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .timestamp(timestamp)
                .build();

        transaction = transactionRepository.save(transaction);

        TransactionEvent event = TransactionEvent.builder()
                .transactionId(transaction.getId())
                .customerId(transaction.getCustomerId())
                .merchantId(transaction.getMerchantId())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .category(transaction.getCategory())
                .location(transaction.getLocation())
                .latitude(transaction.getLatitude())
                .longitude(transaction.getLongitude())
                .timestamp(transaction.getTimestamp())
                .build();

        producer.publish(event);
        log.info("Transaction {} submitted and queued for evaluation", transaction.getId());
        return transaction.getId();
    }
}
