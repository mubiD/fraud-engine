package com.fraudengine.consumer;

import com.fraudengine.config.FraudMetrics;
import com.fraudengine.engine.RuleEngine;
import com.fraudengine.kafka.AssessmentProducer;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Disposition;
import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.proto.TransactionEventProto;
import com.fraudengine.repository.FraudAssessmentRepository;
import com.fraudengine.repository.TransactionRepository;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionConsumerTest {

    @Mock TransactionRepository transactionRepository;
    @Mock FraudAssessmentRepository fraudAssessmentRepository;
    @Mock RuleEngine ruleEngine;
    @Mock AssessmentProducer assessmentProducer;
    @Mock FraudMetrics metrics;
    @Mock Timer evaluationTimer;

    @Captor ArgumentCaptor<Transaction> txCaptor;

    TransactionConsumer consumer;

    static final UUID TX_ID    = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    static final String TOPIC  = "transactions.raw";
    static final int PARTITION = 0;
    static final long OFFSET   = 42L;

    @BeforeEach
    void setUp() {
        consumer = new TransactionConsumer(
                transactionRepository, fraudAssessmentRepository,
                ruleEngine, assessmentProducer, metrics);
        lenient().when(metrics.evaluationTimer()).thenReturn(evaluationTimer);
    }

    // ── consume() — new transaction path ────────────────────────────────────

    @Test
    void consume_newTransaction_persistsThenEvaluatesAndPublishes() {
        TransactionEventProto.TransactionEvent event = buildEvent(TX_ID);
        Transaction saved = buildTransaction(TX_ID);
        FraudAssessment assessment = buildAssessment(saved, Disposition.CLEARED, 0);

        when(transactionRepository.findByIdOnly(TX_ID)).thenReturn(Optional.empty());
        when(transactionRepository.save(any(Transaction.class))).thenReturn(saved);
        when(ruleEngine.evaluate(saved)).thenReturn(assessment);

        consumer.consume(event, TOPIC, PARTITION, OFFSET);

        // initial persist from proto + status-update save
        verify(transactionRepository, times(2)).save(any(Transaction.class));
        verify(fraudAssessmentRepository).save(assessment);
        verify(assessmentProducer).publish(saved, assessment);
    }

    @Test
    void consume_existingTransaction_skipsInitialPersist() {
        TransactionEventProto.TransactionEvent event = buildEvent(TX_ID);
        Transaction existing = buildTransaction(TX_ID);
        FraudAssessment assessment = buildAssessment(existing, Disposition.CLEARED, 0);

        when(transactionRepository.findByIdOnly(TX_ID)).thenReturn(Optional.of(existing));
        when(ruleEngine.evaluate(existing)).thenReturn(assessment);

        consumer.consume(event, TOPIC, PARTITION, OFFSET);

        // only the status-update save, not a second entity persist
        verify(transactionRepository, times(1)).save(existing);
    }

    @Test
    void consume_setsTransactionStatusToAssessedBeforePublish() {
        TransactionEventProto.TransactionEvent event = buildEvent(TX_ID);
        Transaction tx = buildTransaction(TX_ID);
        FraudAssessment assessment = buildAssessment(tx, Disposition.CLEARED, 0);

        when(transactionRepository.findByIdOnly(TX_ID)).thenReturn(Optional.of(tx));
        when(ruleEngine.evaluate(tx)).thenReturn(assessment);

        consumer.consume(event, TOPIC, PARTITION, OFFSET);

        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getStatus()).isEqualTo(TransactionStatus.ASSESSED);
    }

    @Test
    void consume_assessmentSavedBeforeTransactionStatusUpdate() {
        TransactionEventProto.TransactionEvent event = buildEvent(TX_ID);
        Transaction tx = buildTransaction(TX_ID);
        FraudAssessment assessment = buildAssessment(tx, Disposition.CLEARED, 0);

        when(transactionRepository.findByIdOnly(TX_ID)).thenReturn(Optional.of(tx));
        when(ruleEngine.evaluate(tx)).thenReturn(assessment);

        consumer.consume(event, TOPIC, PARTITION, OFFSET);

        InOrder order = inOrder(fraudAssessmentRepository, transactionRepository, assessmentProducer);
        order.verify(fraudAssessmentRepository).save(assessment);
        order.verify(transactionRepository).save(tx);
        order.verify(assessmentProducer).publish(tx, assessment);
    }

    // ── consume() — metrics ─────────────────────────────────────────────────

    @Test
    void consume_flaggedAssessment_incrementsFlaggedCounter() {
        TransactionEventProto.TransactionEvent event = buildEvent(TX_ID);
        Transaction tx = buildTransaction(TX_ID);

        when(transactionRepository.findByIdOnly(TX_ID)).thenReturn(Optional.of(tx));
        when(ruleEngine.evaluate(tx)).thenReturn(buildAssessment(tx, Disposition.FLAGGED, 75));

        consumer.consume(event, TOPIC, PARTITION, OFFSET);

        verify(metrics).recordFlagged();
        verify(metrics, never()).recordCleared();
        verify(metrics, never()).recordPendingReview();
    }

    @Test
    void consume_pendingReviewAssessment_incrementsPendingReviewCounter() {
        TransactionEventProto.TransactionEvent event = buildEvent(TX_ID);
        Transaction tx = buildTransaction(TX_ID);

        when(transactionRepository.findByIdOnly(TX_ID)).thenReturn(Optional.of(tx));
        when(ruleEngine.evaluate(tx)).thenReturn(buildAssessment(tx, Disposition.PENDING_REVIEW, 20));

        consumer.consume(event, TOPIC, PARTITION, OFFSET);

        verify(metrics).recordPendingReview();
        verify(metrics, never()).recordFlagged();
        verify(metrics, never()).recordCleared();
    }

    @Test
    void consume_clearedAssessment_incrementsClearedCounter() {
        TransactionEventProto.TransactionEvent event = buildEvent(TX_ID);
        Transaction tx = buildTransaction(TX_ID);

        when(transactionRepository.findByIdOnly(TX_ID)).thenReturn(Optional.of(tx));
        when(ruleEngine.evaluate(tx)).thenReturn(buildAssessment(tx, Disposition.CLEARED, 0));

        consumer.consume(event, TOPIC, PARTITION, OFFSET);

        verify(metrics).recordCleared();
        verify(metrics, never()).recordFlagged();
        verify(metrics, never()).recordPendingReview();
    }

    @Test
    void consume_recordsEvaluationTimerLatency() {
        TransactionEventProto.TransactionEvent event = buildEvent(TX_ID);
        Transaction tx = buildTransaction(TX_ID);

        when(transactionRepository.findByIdOnly(TX_ID)).thenReturn(Optional.of(tx));
        when(ruleEngine.evaluate(tx)).thenReturn(buildAssessment(tx, Disposition.CLEARED, 0));

        consumer.consume(event, TOPIC, PARTITION, OFFSET);

        verify(metrics, atLeastOnce()).evaluationTimer();
    }

    // ── handleDlt() ──────────────────────────────────────────────────────────

    @Test
    void handleDlt_existingTransaction_setsStatusToFailed() {
        TransactionEventProto.TransactionEvent event = buildEvent(TX_ID);
        Transaction tx = buildTransaction(TX_ID);

        when(transactionRepository.findByIdOnly(TX_ID)).thenReturn(Optional.of(tx));

        consumer.handleDlt(event, "transactions.raw.DLT");

        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void handleDlt_unknownTransaction_doesNotSave() {
        TransactionEventProto.TransactionEvent event = buildEvent(TX_ID);

        when(transactionRepository.findByIdOnly(TX_ID)).thenReturn(Optional.empty());

        consumer.handleDlt(event, "transactions.raw.DLT");

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void handleDlt_alwaysRecordsDltMetric() {
        TransactionEventProto.TransactionEvent event = buildEvent(TX_ID);

        when(transactionRepository.findByIdOnly(TX_ID)).thenReturn(Optional.empty());

        consumer.handleDlt(event, "transactions.raw.DLT");

        verify(metrics).recordDlt();
    }

    @Test
    void handleDlt_neverCallsRuleEngineOrProducer() {
        TransactionEventProto.TransactionEvent event = buildEvent(TX_ID);

        when(transactionRepository.findByIdOnly(TX_ID)).thenReturn(Optional.empty());

        consumer.handleDlt(event, "transactions.raw.DLT");

        verifyNoInteractions(ruleEngine, fraudAssessmentRepository, assessmentProducer);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private TransactionEventProto.TransactionEvent buildEvent(UUID id) {
        Instant now = Instant.now();
        return TransactionEventProto.TransactionEvent.newBuilder()
                .setTransactionId(id.toString())
                .setCustomerId("CUST-001")
                .setMerchantId("MERCH-001")
                .setAmount("500.00")
                .setCurrency("ZAR")
                .setCategory("RETAIL")
                .setTransactionType(TransactionEventProto.TransactionType.CARD_PRESENT)
                .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(now.getEpochSecond())
                        .setNanos(now.getNano())
                        .build())
                .build();
    }

    private Transaction buildTransaction(UUID id) {
        return Transaction.builder()
                .id(id)
                .customerId("CUST-001")
                .merchantId("MERCH-001")
                .amount(new BigDecimal("500.00"))
                .currency("ZAR")
                .transactionType(TransactionType.CARD_PRESENT)
                .timestamp(Instant.now())
                .status(TransactionStatus.PENDING)
                .build();
    }

    private FraudAssessment buildAssessment(Transaction tx, Disposition disposition, int score) {
        FraudAssessment a = FraudAssessment.builder()
                .transaction(tx)
                .disposition(disposition)
                .riskScore(score)
                .build();
        a.setRuleViolations(List.of());
        return a;
    }
}
