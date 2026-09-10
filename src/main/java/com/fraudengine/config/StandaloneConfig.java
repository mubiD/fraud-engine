package com.fraudengine.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * STANDALONE STUB: active only with --spring.profiles.active=standalone.
 *
 * Replaces the transaction manager that external infrastructure provides in production:
 * KafkaConfig.chainedKafkaTransactionManager() is the @Primary transaction manager there,
 * registered under the bean name "transactionManager" (chaining a KafkaTransactionManager
 * with a JpaTransactionManager) so that Kafka message publishing and the DB write commit or
 * roll back atomically (exactly-once semantics). Without Kafka, a plain JpaTransactionManager
 * is sufficient. And this bean's own method name ("transactionManager") is exactly why this
 * profile never hit the bean-name-resolution bug production did: Spring Data JPA repositories
 * default to looking up a transaction manager by that exact name when more than one candidate
 * exists, and this is the only one that ever exists under this profile.
 */
@Configuration
@Profile("standalone")
public class StandaloneConfig {

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
