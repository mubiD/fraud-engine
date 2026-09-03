package com.fraudengine.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class FraudMetrics {

    private final Counter flaggedCounter;
    private final Counter pendingReviewCounter;
    private final Counter clearedCounter;
    private final Counter dltCounter;
    private final Counter duplicateDeliveryCounter;
    private final Timer evaluationTimer;

    public FraudMetrics(MeterRegistry registry) {
        this.flaggedCounter = Counter.builder("fraud.assessments.total")
                .description("Total fraud assessments")
                .tag("verdict", "FLAGGED")
                .register(registry);

        this.pendingReviewCounter = Counter.builder("fraud.assessments.total")
                .description("Total fraud assessments")
                .tag("verdict", "PENDING_REVIEW")
                .register(registry);

        this.clearedCounter = Counter.builder("fraud.assessments.total")
                .description("Total fraud assessments")
                .tag("verdict", "CLEARED")
                .register(registry);

        this.dltCounter = Counter.builder("fraud.dlt.total")
                .description("Transactions exhausted all retries and reached the dead-letter topic")
                .register(registry);

        this.duplicateDeliveryCounter = Counter.builder("fraud.kafka.duplicate_delivery.total")
                .description("Kafka redeliveries of a transaction that was already assessed, skipped for idempotency")
                .register(registry);

        this.evaluationTimer = Timer.builder("fraud.rule.evaluation.duration.seconds")
                .description("Rule engine evaluation latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void recordFlagged()       { flaggedCounter.increment(); }
    public void recordPendingReview() { pendingReviewCounter.increment(); }
    public void recordCleared()       { clearedCounter.increment(); }
    public void recordDlt()           { dltCounter.increment(); }
    public void recordDuplicateDelivery() { duplicateDeliveryCounter.increment(); }
    public Timer evaluationTimer()    { return evaluationTimer; }
}
