package com.fraudengine.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Custom health endpoint to expose state store status as a separate endpoint.
 * Used by Kubernetes startup probe to check if Kafka Streams state store is ready.
 *
 * Endpoints:
 *   GET /actuator/health/custom/state-store — state store readiness (for startup probe)
 *   GET /actuator/health/custom/kafka-consumer — kafka consumer readiness
 */
@RestController
@RequestMapping("/actuator/health/custom")
@Profile("!standalone & !local")
public class CustomHealthEndpoint {

    private final StateStoreHealthIndicator stateStoreHealth;
    private final KafkaConsumerReadinessIndicator kafkaConsumerHealth;
    private final HealthEndpoint healthEndpoint;

    public CustomHealthEndpoint(
            StateStoreHealthIndicator stateStoreHealth,
            KafkaConsumerReadinessIndicator kafkaConsumerHealth,
            HealthEndpoint healthEndpoint) {
        this.stateStoreHealth = stateStoreHealth;
        this.kafkaConsumerHealth = kafkaConsumerHealth;
        this.healthEndpoint = healthEndpoint;
    }

    /**
     * State store readiness endpoint.
     * Used by Kubernetes startup probe to determine when state store is ready for queries.
     */
    @GetMapping("/state-store")
    public Health stateStoreStatus() {
        return stateStoreHealth.health();
    }

    /**
     * Kafka consumer readiness endpoint.
     * Used by readiness probe to determine when consumer has processed first message.
     */
    @GetMapping("/kafka-consumer")
    public Health kafkaConsumerStatus() {
        return kafkaConsumerHealth.health();
    }

    /**
     * Combined readiness check: both state store and kafka consumer must be ready.
     * This is what Spring Boot's standard readiness probe uses.
     */
    @GetMapping("/readiness-combined")
    public Health combinedReadiness() {
        Health stateStore = stateStoreHealth.health();
        Health consumer = kafkaConsumerHealth.health();

        if ("UP".equals(stateStore.getStatus().toString()) &&
            "UP".equals(consumer.getStatus().toString())) {
            return Health.up()
                    .withDetail("stateStore", stateStore.getStatus().toString())
                    .withDetail("kafkaConsumer", consumer.getStatus().toString())
                    .build();
        }

        return Health.down()
                .withDetail("stateStore", stateStore.getStatus().toString())
                .withDetail("kafkaConsumer", consumer.getStatus().toString())
                .withDetail("details", "Not all components ready")
                .build();
    }
}
