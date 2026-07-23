package com.fraudengine.consumer;

import com.fraudengine.config.FraudMetrics;
import com.fraudengine.engine.RuleEngine;
import com.fraudengine.kafka.AssessmentProducer;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.proto.ProtoMapper;
import com.fraudengine.proto.TransactionEventProto;
import com.fraudengine.repository.FraudAssessmentRepository;
import com.fraudengine.repository.TransactionRepository;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class TransactionConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionConsumer.class);

    private final TransactionRepository transactionRepository;
    private final FraudAssessmentRepository fraudAssessmentRepository;
    private final RuleEngine ruleEngine;
    private final AssessmentProducer assessmentProducer;
    private final FraudMetrics metrics;

    public TransactionConsumer(TransactionRepository transactionRepository,
                                FraudAssessmentRepository fraudAssessmentRepository,
                                RuleEngine ruleEngine,
                                AssessmentProducer assessmentProducer,
                                FraudMetrics metrics) {
        this.transactionRepository = transactionRepository;
        this.fraudAssessmentRepository = fraudAssessmentRepository;
        this.ruleEngine = ruleEngine;
        this.assessmentProducer = assessmentProducer;
        this.metrics = metrics;
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
    @Transactional(transactionManager = "chainedKafkaTransactionManager")
    public void consume(TransactionEventProto.TransactionEvent event,
                        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        try {
            MDC.put("transactionId", event.getTransactionId());
            MDC.put("kafkaTopic",    topic);
            MDC.put("kafkaPartition", String.valueOf(partition));
            MDC.put("kafkaOffset",   String.valueOf(offset));

            log.info("Consumed transaction event: amount={} {}, category={}, location={}",
                    event.getAmount(), event.getCurrency(),
                    event.getCategory(), event.getLocation());

            Transaction transaction = transactionRepository
                    .findByIdOnly(UUID.fromString(event.getTransactionId()))
                    .orElseGet(() -> transactionRepository.save(ProtoMapper.toTransactionEntity(event)));

            Timer.Sample sample = Timer.start();
            FraudAssessment assessment = ruleEngine.evaluate(transaction);
            sample.stop(metrics.evaluationTimer());

            fraudAssessmentRepository.save(assessment);

            transaction.setStatus(TransactionStatus.ASSESSED);
            transactionRepository.save(transaction);

            assessmentProducer.publish(transaction, assessment);

            if (assessment.isFraudulent()) {
                metrics.recordFraudulent();
                log.warn("Transaction flagged as FRAUDULENT: riskScore={}, violations={}",
                        assessment.getRiskScore(), assessment.getRuleViolations().size());
            } else {
                metrics.recordPassed();
                log.info("Transaction cleared: riskScore={}", assessment.getRiskScore());
            }
        } finally {
            MDC.clear();
        }
    }

    @DltHandler
    public void handleDlt(TransactionEventProto.TransactionEvent event,
                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        try {
            MDC.put("transactionId", event.getTransactionId());
            MDC.put("kafkaTopic",    topic);

            metrics.recordDlt();
            log.error("Transaction exhausted all retries and was routed to DLT: topic={}", topic);

            transactionRepository.findByIdOnly(UUID.fromString(event.getTransactionId())).ifPresent(t -> {
                t.setStatus(TransactionStatus.FAILED);
                transactionRepository.save(t);
                log.error("Transaction status set to FAILED");
            });
        } finally {
            MDC.clear();
        }
    }
}
