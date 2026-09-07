package com.fraudengine.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * STANDALONE STUB — active only with --spring.profiles.active=standalone.
 *
 * Replaces the transaction manager that external infrastructure provides in production:
 * KafkaConfig.chainedKafkaTransactionManager() is the @Primary transaction manager there,
 * chaining a KafkaTransactionManager with a DataSourceTransactionManager so that Kafka
 * message publishing and the DB write commit or roll back atomically (exactly-once
 * semantics). Without Kafka, a plain JpaTransactionManager is sufficient.
 */
@Configuration
@Profile("standalone")
public class StandaloneConfig {

    /**
     * STUB: Plain JPA transaction manager.
     * Production replacement: KafkaConfig.chainedKafkaTransactionManager() — chains
     * Kafka + DB transactions to guarantee exactly-once delivery.
     */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
