package com.fraudengine.consumer;

import com.fraudengine.engine.RuleEngine;
import com.fraudengine.kafka.TransactionEvent;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.repository.FraudAssessmentRepository;
import com.fraudengine.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionConsumer.class);

    private final TransactionRepository transactionRepository;
    private final FraudAssessmentRepository fraudAssessmentRepository;
    private final RuleEngine ruleEngine;

    public TransactionConsumer(TransactionRepository transactionRepository,
                                FraudAssessmentRepository fraudAssessmentRepository,
                                RuleEngine ruleEngine) {
        this.transactionRepository = transactionRepository;
        this.fraudAssessmentRepository = fraudAssessmentRepository;
        this.ruleEngine = ruleEngine;
    }

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltTopicSuffix = ".DLT"
    )
    @KafkaListener(
            topics = "${fraud.kafka.topics.transactions-raw}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consume(TransactionEvent event) {
        log.info("Received transaction event: {}", event.getTransactionId());

        Transaction transaction = transactionRepository.findById(event.getTransactionId())
                .orElseGet(() -> transactionRepository.save(mapToEntity(event)));

        FraudAssessment assessment = ruleEngine.evaluate(transaction);
        fraudAssessmentRepository.save(assessment);

        transaction.setStatus(TransactionStatus.ASSESSED);
        transactionRepository.save(transaction);

        log.info("Assessment complete for transaction {}: fraudulent={}, score={}",
                transaction.getId(), assessment.isFraudulent(), assessment.getRiskScore());
    }

    @DltHandler
    public void handleDlt(TransactionEvent event,
                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("Transaction {} exhausted retries, routed to DLT from topic: {}",
                event.getTransactionId(), topic);
        transactionRepository.findById(event.getTransactionId()).ifPresent(t -> {
            t.setStatus(TransactionStatus.FAILED);
            transactionRepository.save(t);
        });
    }

    private Transaction mapToEntity(TransactionEvent event) {
        return Transaction.builder()
                .id(event.getTransactionId())
                .customerId(event.getCustomerId())
                .merchantId(event.getMerchantId())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .category(event.getCategory())
                .location(event.getLocation())
                .latitude(event.getLatitude())
                .longitude(event.getLongitude())
                .timestamp(event.getTimestamp())
                .build();
    }
}
