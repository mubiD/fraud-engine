package com.fraudengine.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("!standalone & !local")
public class KafkaConsumerReadinessIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerReadinessIndicator.class);

    private final AtomicBoolean isReady = new AtomicBoolean(false);
    private volatile long firstMessageReceivedTime = -1;

    @Override
    public Health health() {
        if (!isReady.get()) {
            return Health.down()
                    .withDetail("reason", "Kafka consumer not yet ready")
                    .withDetail("firstMessageTime", firstMessageReceivedTime >= 0 ? firstMessageReceivedTime : "not received")
                    .build();
        }

        return Health.up()
                .withDetail("reason", "Kafka consumer is ready and processing messages")
                .withDetail("firstMessageReceivedAt", firstMessageReceivedTime)
                .build();
    }

    public void markAsReady() {
        if (firstMessageReceivedTime < 0) {
            firstMessageReceivedTime = System.currentTimeMillis();
            log.info("Kafka consumer processed first message, marking as ready for traffic");
        }
        isReady.set(true);
    }

    public boolean isReady() {
        return isReady.get();
    }
}
