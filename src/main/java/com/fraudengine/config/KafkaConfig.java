package com.fraudengine.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.transaction.ChainedKafkaTransactionManager;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;

import jakarta.persistence.EntityManagerFactory;
import java.util.Map;

// PRODUCTION ONLY: inactive under the standalone and local Spring profiles.
// Wires the transactional Kafka producer (KafkaProtobufSerializer + Schema Registry),
// topic declarations, and ChainedKafkaTransactionManager for exactly-once DB+Kafka
// commit semantics.
// standalone profile: StandaloneConfig.java provides @Primary JpaTransactionManager.
// local profile:      LocalKafkaConfig.java provides @Primary JpaTransactionManager
//                     and topic declarations; JSON serialisation used instead of Protobuf.
@Configuration
@Profile("!standalone & !local")
public class KafkaConfig {

    @Value("${fraud.kafka.topics.transactions-raw}")
    private String transactionsRawTopic;

    @Value("${fraud.kafka.topics.transactions-flagged}")
    private String transactionsFlaggedTopic;

    @Value("${fraud.kafka.topics.transactions-pending-review}")
    private String transactionsPendingReviewTopic;

    @Value("${fraud.kafka.topics.transactions-passed}")
    private String transactionsPassedTopic;

    @Value("${fraud.kafka.topics.transactions-dlt}")
    private String transactionsDltTopic;

    @Value("${fraud.kafka.replication-factor:1}")
    private int replicationFactor;

    private final KafkaProperties kafkaProperties;

    public KafkaConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    // -------------------------------------------------------------------------
    // Topic declarations
    // -------------------------------------------------------------------------

    @Bean
    public NewTopic transactionsRawTopic() {
        return TopicBuilder.name(transactionsRawTopic).partitions(6).replicas(replicationFactor).build();
    }

    @Bean
    public NewTopic transactionsFlaggedTopic() {
        return TopicBuilder.name(transactionsFlaggedTopic).partitions(3).replicas(replicationFactor).build();
    }

    @Bean
    public NewTopic transactionsPendingReviewTopic() {
        return TopicBuilder.name(transactionsPendingReviewTopic).partitions(3).replicas(replicationFactor).build();
    }

    @Bean
    public NewTopic transactionsPassedTopic() {
        return TopicBuilder.name(transactionsPassedTopic).partitions(3).replicas(replicationFactor).build();
    }

    @Bean
    public NewTopic transactionsDltTopic() {
        // Must have at least as many partitions as transactions.raw (6): Spring Kafka's retry-topic
        // publisher preserves the original record's partition index across every retry hop, including
        // the final DLT publish, and throws if the target topic doesn't have that partition. See
        // TransactionConsumer's @RetryableTopic and KafkaConfigTest.
        return TopicBuilder.name(transactionsDltTopic).partitions(6).replicas(replicationFactor).build();
    }

    // -------------------------------------------------------------------------
    // Transactional producer — required for ChainedKafkaTransactionManager
    // -------------------------------------------------------------------------

    // buildProducerProperties(null) — not a hand-rolled props map — is what makes this
    // producer actually pick up spring.kafka.properties.* (security.protocol, sasl.jaas.config,
    // ssl.truststore.*, etc.). A hand-rolled map here only set bootstrap-servers/serializers
    // directly, silently ignoring every SASL_SSL/TLS property application-prod.yml sets — so
    // this producer tried plaintext against a SASL_SSL-only port (repeated "Bootstrap broker
    // ... disconnected"), found live 2026-09-08, the first time this project ever actually
    // exercised a non-plaintext Kafka listener. buildProducerProperties is the exact same
    // mechanism Spring Boot's own autoconfigured producer/consumer factories use internally,
    // so this now stays correct automatically across every profile, not just prod. The
    // `null` SslBundles argument is fine — that parameter (Spring Boot 3.1+) only matters if
    // spring.kafka.ssl.bundle is used; this project sets ssl.* properties directly instead.
    @Bean
    public ProducerFactory<String, Object> transactionalProducerFactory() {
        Map<String, Object> props = kafkaProperties.buildProducerProperties(null);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);

        DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(props);
        // Unique prefix per instance — required for Kafka transactions.
        // At runtime this becomes "fraud-engine-tx-0", "fraud-engine-tx-1", etc.
        factory.setTransactionIdPrefix("fraud-engine-tx-");
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(transactionalProducerFactory());
    }

    // -------------------------------------------------------------------------
    // Non-transactional producer — retry-topic / DLT publishing only.
    // -------------------------------------------------------------------------

    // Spring Kafka's @RetryableTopic (TransactionConsumer.consume()) looks up a KafkaTemplate
    // bean named exactly "defaultRetryTopicKafkaTemplate" for retry/DLT publishing, falling
    // back to "kafkaTemplate" if absent. Without this bean it was silently reusing the
    // transactional kafkaTemplate() above — but retry/DLT publishing happens from Spring
    // Kafka's error-handling path, invoked AFTER the listener's own Kafka transaction has
    // already rolled back, so a transactional template throws "No transaction is in process"
    // there every time. Found live 2026-09-08, alongside the transactionManager bean-name bug
    // that was the actual trigger for these retries in the first place (KafkaConfig's
    // chainedKafkaTransactionManager() javadoc) — this bean is what makes the retry/DLT path
    // itself work correctly once retries do happen, for any other, genuine failure.
    @Bean
    public KafkaTemplate<String, Object> defaultRetryTopicKafkaTemplate() {
        // Same buildProducerProperties(null) reasoning as transactionalProducerFactory above.
        Map<String, Object> props = kafkaProperties.buildProducerProperties(null);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        // Deliberately no ENABLE_IDEMPOTENCE_CONFIG/transactionIdPrefix — this producer must
        // stay non-transactional.
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Bean
    public KafkaTransactionManager<String, Object> kafkaTransactionManager() {
        return new KafkaTransactionManager<>(transactionalProducerFactory());
    }

    // -------------------------------------------------------------------------
    // Chain Kafka TX + JPA TX so both commit or both roll back.
    // Order: open Kafka TX → open DB TX → work → commit DB TX → commit Kafka TX.
    // If DB commit fails → Kafka TX aborts → no message published → consumer retries.
    // If Kafka commit fails after DB committed (or the offset commit fails after a fully
    //   committed TX) → consumer redelivers → TransactionConsumer's findByTransactionId
    //   guard skips re-evaluation, re-save, and re-publish entirely. Resolved.
    //
    // The DB leg MUST be a JpaTransactionManager, not a plain DataSourceTransactionManager:
    // Hibernate's EntityManager only registers its Session's commit/rollback with a
    // transaction that JpaTransactionManager opened (via SessionSynchronization) — a
    // DataSourceTransactionManager only binds a raw JDBC Connection, which Spring Data JPA
    // repositories don't synchronize against, so the "DB and Kafka commit/roll back
    // together" guarantee this comment describes wouldn't actually hold with one.
    //
    // Named "transactionManager" (not just @Primary) because Spring Data JPA's repository
    // proxies default to looking up a transaction manager BY THAT EXACT BEAN NAME
    // (JpaRepositoriesAutoConfiguration's transactionManagerRef default) whenever more than
    // one PlatformTransactionManager-family bean exists in the context — @Primary alone does
    // not satisfy that by-name lookup. Found live 2026-09-08: every transactionRepository/
    // fraudAssessmentRepository call inside TransactionConsumer.consume() failed immediately
    // with NoSuchBeanDefinitionException for qualifier 'transactionManager', so RuleEngine
    // was never reached — this was the actual root cause, not the DLT/retry-topic issue
    // (KafkaConfigTest fix, elsewhere) that surfaced as a secondary symptom once this
    // exception triggered Spring Kafka's retry path.
    // -------------------------------------------------------------------------

    @Bean("transactionManager")
    @Primary
    public ChainedKafkaTransactionManager<String, Object> chainedKafkaTransactionManager(
            KafkaTransactionManager<String, Object> kafkaTransactionManager,
            EntityManagerFactory entityManagerFactory) {
        JpaTransactionManager jpaTransactionManager = new JpaTransactionManager(entityManagerFactory);
        return new ChainedKafkaTransactionManager<>(kafkaTransactionManager, jpaTransactionManager);
    }
}
