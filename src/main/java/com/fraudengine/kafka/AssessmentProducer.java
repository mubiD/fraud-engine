package com.fraudengine.kafka;

import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.proto.ClearedTransactionEventProto;
import com.fraudengine.proto.FraudulentTransactionEventProto;
import com.fraudengine.proto.PendingReviewTransactionEventProto;
import com.fraudengine.proto.ProtoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// PRODUCTION ONLY: inactive under the standalone and local Spring profiles.
// In production serialises FraudulentTransactionEvent / ClearedTransactionEvent protos
// (Confluent KafkaProtobufSerializer) and publishes to Kafka for downstream alert,
// audit, and BI consumers.
// standalone / local: rule engine runs end-to-end; Kafka publish is skipped.
@Component
@Profile("!standalone & !local")
public class AssessmentProducer {

    private static final Logger log = LoggerFactory.getLogger(AssessmentProducer.class);

    @Value("${fraud.kafka.topics.transactions-flagged}")
    private String flaggedTopic;

    @Value("${fraud.kafka.topics.transactions-pending-review}")
    private String pendingReviewTopic;

    @Value("${fraud.kafka.topics.transactions-passed}")
    private String passedTopic;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public AssessmentProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(Transaction transaction, FraudAssessment assessment) {
        switch (assessment.getDisposition()) {
            case FLAGGED -> {
                FraudulentTransactionEventProto.FraudulentTransactionEvent event =
                        ProtoMapper.toFraudulentProto(transaction, assessment);
                kafkaTemplate.send(flaggedTopic, transaction.getCustomerId(), event);
                log.debug("Published FraudulentTransactionEvent: topic={}, transactionId={}",
                        flaggedTopic, transaction.getId());
            }
            case PENDING_REVIEW -> {
                PendingReviewTransactionEventProto.PendingReviewTransactionEvent event =
                        ProtoMapper.toPendingReviewProto(transaction, assessment);
                kafkaTemplate.send(pendingReviewTopic, transaction.getCustomerId(), event);
                log.debug("Published PendingReviewTransactionEvent: topic={}, transactionId={}",
                        pendingReviewTopic, transaction.getId());
            }
            case CLEARED -> {
                ClearedTransactionEventProto.ClearedTransactionEvent event =
                        ProtoMapper.toClearedProto(transaction);
                kafkaTemplate.send(passedTopic, transaction.getCustomerId(), event);
                log.debug("Published ClearedTransactionEvent: topic={}, transactionId={}",
                        passedTopic, transaction.getId());
            }
        }
    }
}
