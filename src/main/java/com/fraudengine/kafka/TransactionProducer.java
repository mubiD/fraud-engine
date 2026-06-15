package com.fraudengine.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class TransactionProducer {

    private static final Logger log = LoggerFactory.getLogger(TransactionProducer.class);

    @Value("${fraud.kafka.topics.transactions-raw}")
    private String topic;

    private final KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    public TransactionProducer(KafkaTemplate<String, TransactionEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<SendResult<String, TransactionEvent>> publish(TransactionEvent event) {
        log.debug("Publishing transaction {} to topic {}", event.getTransactionId(), topic);
        CompletableFuture<SendResult<String, TransactionEvent>> future =
                kafkaTemplate.send(topic, event.getCustomerId(), event);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish transaction {}: {}", event.getTransactionId(), ex.getMessage());
            } else {
                log.debug("Published transaction {} to partition {}",
                        event.getTransactionId(), result.getRecordMetadata().partition());
            }
        });
        return future;
    }
}
