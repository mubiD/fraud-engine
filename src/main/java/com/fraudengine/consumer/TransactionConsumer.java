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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// PRODUCTION ONLY: inactive under the standalone and local Spring profiles.
// In production consumes transactions.raw (Protobuf/Schema Registry wire format),
// runs the rule engine, persists results, and publishes to transactions.flagged /
// transactions.passed inside a ChainedKafkaTransactionManager transaction for
// exactly-once semantics.
// standalone / local: the equivalent flow is exercised synchronously via
// POST /api/v1/standalone/submit (StandaloneTransactionController.java).
@Component
@Profile("!standalone & !local")
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

    // numPartitions must match transactions.raw's partition count (KafkaConfig.transactionsRawTopic(),
    // currently 6): Spring Kafka's retry-topic publisher preserves the original record's partition
    // index by default, and a retry/DLT topic with fewer partitions than that index throws on publish
    // rather than falling back gracefully. Auto-created retry topics default to 1 partition if this
    // isn't set, which would fail for any message not originally on partition 0. See KafkaConfigTest
    // for the cross-check against transactionsRawTopic()/transactionsDltTopic()'s partition counts.
    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            numPartitions = "6",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltTopicSuffix = ".DLT"
    )
    @KafkaListener(
            topics = "${fraud.kafka.topics.transactions-raw}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    // Bean name is "transactionManager" (KafkaConfig.chainedKafkaTransactionManager()'s
    // @Bean("transactionManager")) — not just the method name — see that bean's javadoc for why.
    @Transactional(transactionManager = "transactionManager")
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

            if (fraudAssessmentRepository.findByTransactionId(transaction.getId()).isPresent()) {
                metrics.recordDuplicateDelivery();
                log.warn("Transaction already assessed — skipping duplicate delivery");
                return;
            }

            // Timing/disposition metrics are recorded inside RuleEngine.evaluate() itself, not here —
            // see its javadoc: that keeps this consumer and StandaloneTransactionController reporting
            // the same counters instead of only the Kafka path doing so.
            FraudAssessment assessment = ruleEngine.evaluate(transaction);

            fraudAssessmentRepository.save(assessment);

            transaction.setStatus(TransactionStatus.ASSESSED);
            transactionRepository.save(transaction);

            assessmentProducer.publish(transaction, assessment);

            switch (assessment.getDisposition()) {
                case FLAGGED -> log.warn("Transaction flagged as FRAUDULENT: riskScore={}, violations={}",
                        assessment.getRiskScore(), assessment.getRuleViolations().size());
                case PENDING_REVIEW -> log.warn("Transaction marked PENDING_REVIEW: riskScore={}, violations={}",
                        assessment.getRiskScore(), assessment.getRuleViolations().size());
                case CLEARED -> log.info("Transaction cleared: riskScore={}", assessment.getRiskScore());
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
