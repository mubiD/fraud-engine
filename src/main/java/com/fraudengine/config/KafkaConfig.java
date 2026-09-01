package com.fraudengine.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.transaction.ChainedKafkaTransactionManager;
import org.springframework.kafka.transaction.KafkaTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
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

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.producer.value-serializer}")
    private String valueSerializer;

    @Value("${fraud.kafka.replication-factor:1}")
    private int replicationFactor;

    @Value("${spring.kafka.properties.schema.registry.url:http://schema-registry:8081}")
    private String schemaRegistryUrl;

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
        return TopicBuilder.name(transactionsDltTopic).partitions(1).replicas(replicationFactor).build();
    }

    // -------------------------------------------------------------------------
    // Transactional producer — required for ChainedKafkaTransactionManager
    // -------------------------------------------------------------------------

    @Bean
    public ProducerFactory<String, Object> transactionalProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put("schema.registry.url", schemaRegistryUrl);

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

    @Bean
    public KafkaTransactionManager<String, Object> kafkaTransactionManager() {
        return new KafkaTransactionManager<>(transactionalProducerFactory());
    }

    // -------------------------------------------------------------------------
    // Chain Kafka TX + JPA TX so both commit or both roll back.
    // Order: open Kafka TX → open DB TX → work → commit DB TX → commit Kafka TX.
    // If DB commit fails → Kafka TX aborts → no message published → consumer retries.
    // If Kafka commit fails after DB committed → consumer retries → idempotency
    //   guard skips re-save → re-publishes. Resolved.
    // -------------------------------------------------------------------------

    @Bean
    @Primary
    public ChainedKafkaTransactionManager<String, Object> chainedKafkaTransactionManager(
            KafkaTransactionManager<String, Object> kafkaTransactionManager,
            DataSource dataSource) {
        DataSourceTransactionManager dataSourceTxManager = new DataSourceTransactionManager(dataSource);
        return new ChainedKafkaTransactionManager<>(kafkaTransactionManager, dataSourceTxManager);
    }
}
