package com.fraudengine.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.Map;
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
        log.debug("Publishing to topic={} partitionKey={}", topic, event.getCustomerId());

        // Capture MDC before the async callback — the callback runs on a Kafka
        // thread that has no MDC context of its own.
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        CompletableFuture<SendResult<String, TransactionEvent>> future =
                kafkaTemplate.send(topic, event.getCustomerId(), event);

        future.whenComplete((result, ex) -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (mdcSnapshot != null) MDC.setContextMap(mdcSnapshot);
                if (ex != null) {
                    log.error("Kafka publish failed: topic={}, error={}", topic, ex.getMessage(), ex);
                } else {
                    log.debug("Kafka publish confirmed: topic={}, partition={}, offset={}",
                            topic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            } finally {
                if (previous != null) MDC.setContextMap(previous); else MDC.clear();
            }
        });

        return future;
    }
}
