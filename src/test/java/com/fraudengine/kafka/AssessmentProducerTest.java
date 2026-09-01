package com.fraudengine.kafka;

import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Disposition;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.proto.ClearedTransactionEventProto;
import com.fraudengine.proto.FraudulentTransactionEventProto;
import com.fraudengine.proto.PendingReviewTransactionEventProto;
import com.fraudengine.proto.TransactionEventProto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AssessmentProducerTest {

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    @Captor
    ArgumentCaptor<Object> eventCaptor;

    AssessmentProducer producer;

    @BeforeEach
    void setUp() {
        producer = new AssessmentProducer(kafkaTemplate);
        ReflectionTestUtils.setField(producer, "flaggedTopic", "transactions.flagged");
        ReflectionTestUtils.setField(producer, "pendingReviewTopic", "transactions.pending-review");
        ReflectionTestUtils.setField(producer, "passedTopic",  "transactions.passed");
    }

    @Test
    void flaggedAssessment_routesToFlaggedTopicWithFraudulentEvent() {
        Transaction tx = buildTransaction();
        producer.publish(tx, buildAssessment(tx, Disposition.FLAGGED, 75));

        verify(kafkaTemplate).send(
                eq("transactions.flagged"),
                eq(tx.getCustomerId()),
                any(FraudulentTransactionEventProto.FraudulentTransactionEvent.class));
    }

    @Test
    void pendingReviewAssessment_routesToPendingReviewTopicWithPendingReviewEvent() {
        Transaction tx = buildTransaction();
        producer.publish(tx, buildAssessment(tx, Disposition.PENDING_REVIEW, 20));

        verify(kafkaTemplate).send(
                eq("transactions.pending-review"),
                eq(tx.getCustomerId()),
                any(PendingReviewTransactionEventProto.PendingReviewTransactionEvent.class));
    }

    @Test
    void clearedAssessment_routesToPassedTopicWithClearedEvent() {
        Transaction tx = buildTransaction();
        producer.publish(tx, buildAssessment(tx, Disposition.CLEARED, 0));

        verify(kafkaTemplate).send(
                eq("transactions.passed"),
                eq(tx.getCustomerId()),
                any(ClearedTransactionEventProto.ClearedTransactionEvent.class));
    }

    @Test
    void fraudulentEvent_containsTransactionIdAndRiskScore() {
        Transaction tx = buildTransaction();
        producer.publish(tx, buildAssessment(tx, Disposition.FLAGGED, 80));

        verify(kafkaTemplate).send(anyString(), anyString(), eventCaptor.capture());
        FraudulentTransactionEventProto.FraudulentTransactionEvent event =
                (FraudulentTransactionEventProto.FraudulentTransactionEvent) eventCaptor.getValue();

        assertThat(event.getTransactionId()).isEqualTo(tx.getId().toString());
        assertThat(event.getCustomerId()).isEqualTo("CUST_TEST");
        assertThat(event.getRiskScore()).isEqualTo(80);
        assertThat(event.getAssessedAt().getSeconds()).isGreaterThan(0);
    }

    @Test
    void pendingReviewEvent_containsFullPaymentDetailsAndRiskScore() {
        Transaction tx = buildTransaction();
        producer.publish(tx, buildAssessment(tx, Disposition.PENDING_REVIEW, 20));

        verify(kafkaTemplate).send(anyString(), anyString(), eventCaptor.capture());
        PendingReviewTransactionEventProto.PendingReviewTransactionEvent event =
                (PendingReviewTransactionEventProto.PendingReviewTransactionEvent) eventCaptor.getValue();

        assertThat(event.getTransactionId()).isEqualTo(tx.getId().toString());
        assertThat(event.getMerchantId()).isEqualTo("MERCH_TEST");
        assertThat(event.getAmount()).isEqualTo("150.00");
        assertThat(event.getCurrency()).isEqualTo("GBP");
        assertThat(event.getTransactionType())
                .isEqualTo(TransactionEventProto.TransactionType.CARD_PRESENT);
        assertThat(event.getRiskScore()).isEqualTo(20);
    }

    @Test
    void clearedEvent_containsFullPaymentDetails() {
        Transaction tx = buildTransaction();
        producer.publish(tx, buildAssessment(tx, Disposition.CLEARED, 0));

        verify(kafkaTemplate).send(anyString(), anyString(), eventCaptor.capture());
        ClearedTransactionEventProto.ClearedTransactionEvent event =
                (ClearedTransactionEventProto.ClearedTransactionEvent) eventCaptor.getValue();

        assertThat(event.getTransactionId()).isEqualTo(tx.getId().toString());
        assertThat(event.getMerchantId()).isEqualTo("MERCH_TEST");
        assertThat(event.getAmount()).isEqualTo("150.00");
        assertThat(event.getCurrency()).isEqualTo("GBP");
        assertThat(event.getTransactionType())
                .isEqualTo(TransactionEventProto.TransactionType.CARD_PRESENT);
    }

    @Test
    void messageKey_isCustomerId_forPartitionOrdering() {
        Transaction tx = buildTransaction();
        producer.publish(tx, buildAssessment(tx, Disposition.CLEARED, 0));

        verify(kafkaTemplate).send(anyString(), eq("CUST_TEST"), any());
    }

    private Transaction buildTransaction() {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .customerId("CUST_TEST")
                .merchantId("MERCH_TEST")
                .amount(new BigDecimal("150.00"))
                .currency("GBP")
                .transactionType(TransactionType.CARD_PRESENT)
                .timestamp(Instant.now())
                .build();
    }

    private FraudAssessment buildAssessment(Transaction tx, Disposition disposition, int riskScore) {
        return FraudAssessment.builder()
                .transaction(tx)
                .disposition(disposition)
                .riskScore(riskScore)
                .build();
    }
}
