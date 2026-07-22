package com.fraudengine.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class FraudMetrics {

    private final Counter fraudulentCounter;
    private final Counter passedCounter;
    private final Timer evaluationTimer;

    public FraudMetrics(MeterRegistry registry) {
        this.fraudulentCounter = Counter.builder("fraud.assessments.total")
                .description("Total fraud assessments")
                .tag("verdict", "FRAUDULENT")
                .register(registry);

        this.passedCounter = Counter.builder("fraud.assessments.total")
                .description("Total fraud assessments")
                .tag("verdict", "PASSED")
                .register(registry);

        this.evaluationTimer = Timer.builder("fraud.rule.evaluation.duration.seconds")
                .description("Rule engine evaluation latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void recordFraudulent() { fraudulentCounter.increment(); }
    public void recordPassed()     { passedCounter.increment(); }
    public Timer evaluationTimer() { return evaluationTimer; }
}
