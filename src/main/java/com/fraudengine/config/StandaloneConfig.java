package com.fraudengine.config;

import com.fraudengine.model.BlacklistedMerchant;
import com.fraudengine.repository.BlacklistedMerchantRepository;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * STANDALONE STUB — active only with --spring.profiles.active=standalone.
 *
 * Replaces two things that external infrastructure provides in production:
 *
 * 1. Transaction manager: In production, KafkaConfig.chainedKafkaTransactionManager()
 *    is the @Primary transaction manager. It chains a KafkaTransactionManager with a
 *    DataSourceTransactionManager so that Kafka message publishing and the DB write
 *    commit or roll back atomically (exactly-once semantics). Without Kafka, a plain
 *    JpaTransactionManager is sufficient.
 *
 * 2. Seed data: In production, Flyway migration V2__seed_blacklisted_merchants.sql
 *    inserts known fraudulent merchants at schema-creation time. Flyway is disabled in
 *    standalone mode (see application-standalone.yml), so we seed the same rows
 *    programmatically here instead.
 */
@Configuration
@Profile("standalone")
public class StandaloneConfig {

    private static final Logger log = LoggerFactory.getLogger(StandaloneConfig.class);

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

    /**
     * STUB: Programmatic seed in lieu of Flyway migration V2.
     * The merchant IDs here must match V2__seed_blacklisted_merchants.sql. No rule
     * reads this data anymore (BlacklistedMerchantRule was removed — see DESIGN.md
     * §5) but the table/cache path was deliberately kept, so this keeps standalone
     * and production presenting identical reference data through it regardless.
     */
    @Bean
    public CommandLineRunner seedBlacklistedMerchants(BlacklistedMerchantRepository repo) {
        return args -> {
            if (repo.count() > 0) {
                return;
            }
            repo.saveAll(List.of(
                    merchant("MERCHANT_FRAUD_001", "Known phishing merchant"),
                    merchant("MERCHANT_FRAUD_002", "Card skimming operation"),
                    merchant("MERCHANT_FRAUD_003", "Synthetic identity fraud ring")
            ));
            log.info("[standalone] Seeded {} blacklisted merchants", 3);
        };
    }

    private BlacklistedMerchant merchant(String id, String reason) {
        BlacklistedMerchant m = new BlacklistedMerchant();
        m.setMerchantId(id);
        m.setReason(reason);
        return m;
    }
}
