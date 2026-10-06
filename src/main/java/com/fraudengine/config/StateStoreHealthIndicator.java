package com.fraudengine.config;

import com.fraudengine.streams.KafkaStreamsRecentActivityStore;
import org.apache.kafka.streams.KafkaStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

@Component
@Profile("!standalone & !local")
public class StateStoreHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(StateStoreHealthIndicator.class);

    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;

    public StateStoreHealthIndicator(StreamsBuilderFactoryBean streamsBuilderFactoryBean) {
        this.streamsBuilderFactoryBean = streamsBuilderFactoryBean;
    }

    @Override
    public Health health() {
        try {
            KafkaStreams kafkaStreams = streamsBuilderFactoryBean.getKafkaStreams();

            if (kafkaStreams == null) {
                log.debug("Kafka Streams application not yet initialized");
                return Health.down()
                        .withDetail("reason", "Kafka Streams application not initialized")
                        .build();
            }

            KafkaStreams.State state = kafkaStreams.state();
            log.debug("Kafka Streams state: {}", state);

            switch (state) {
                case CREATED, RUNNING -> {
                    // State store is ready for queries
                    return Health.up()
                            .withDetail("kafkaStreamsState", state.name())
                            .withDetail("storeName", "customer-activity-store")
                            .build();
                }
                case REBALANCING -> {
                    // Rebalancing in progress, store temporarily unavailable but expected
                    return Health.status("REBALANCING")
                            .withDetail("kafkaStreamsState", state.name())
                            .withDetail("reason", "Kafka Streams rebalancing in progress")
                            .build();
                }
                case PENDING_SHUTDOWN, NOT_RUNNING -> {
                    // Shutting down or not running
                    return Health.down()
                            .withDetail("kafkaStreamsState", state.name())
                            .build();
                }
                case ERROR -> {
                    // Error state, critical
                    return Health.down()
                            .withDetail("kafkaStreamsState", state.name())
                            .withDetail("reason", "Kafka Streams in ERROR state")
                            .build();
                }
                default -> {
                    return Health.unknown()
                            .withDetail("kafkaStreamsState", state.name())
                            .build();
                }
            }
        } catch (Exception e) {
            log.error("Error checking state store health", e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
