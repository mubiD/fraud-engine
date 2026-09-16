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
 * is sufficient, and this bean's own method name ("transactionManager") is exactly why this
 * profile never hit the bean-name-resolution bug production did: Spring Data JPA repositories
 * default to looking up a transaction manager by that exact name when more than one candidate
 * exists.
 *
 * Two PlatformTransactionManager beans exist here (this one, @Primary, plus
 * jpaTransactionManager below) — both plain JPA, functionally identical under this
 * Kafka-free profile. The second name exists purely so TransactionQueryService's
 * isolation-level-sensitive queries can reference "jpaTransactionManager" by the same name
 * across every profile, including load-test/prod where that name is NOT interchangeable
 * with "transactionManager" (see jpaTransactionManager()'s own comment).
 */
@Configuration
@Profile("standalone")
public class StandaloneConfig {

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    // Same plain JPA manager under a second, explicit name: TransactionQueryService's
    // isolation-level-sensitive summary queries (getFraudSummary/getCustomerRiskSummary/
    // getMerchantRiskSummary) reference "jpaTransactionManager" by name so the same
    // @Transactional annotation works under every profile — under load-test/prod
    // (KafkaConfig.java), "transactionManager" is the chained Kafka+JPA manager, which
    // can't honor an isolation level at all (see that bean's own comment on this).
    @Bean("jpaTransactionManager")
    public PlatformTransactionManager jpaTransactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
