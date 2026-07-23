package com.fraudengine.config;

import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * LOCAL STUB — active when SPRING_PROFILES_ACTIVE=local.
 *
 * Provides two things that the full KafkaConfig.java normally owns but cannot be
 * activated in local builds (Confluent JARs not on classpath):
 *
 * 1. Topic declarations — replication-factor 1 for single-node dev Kafka.
 *    Production (KafkaConfig.java): 6 partitions + 2 replicas for transactions.raw,
 *    sized for throughput and fault tolerance.
 *
 * 2. @Primary JpaTransactionManager — Spring Boot auto-configures a
 *    KafkaTransactionManager alongside the transactional producer factory
 *    (fraud-engine-tx- prefix from application.yml). Marking the JPA manager @Primary
 *    ensures @Transactional in StandaloneTransactionController uses JPA, not Kafka.
 *    Production: ChainedKafkaTransactionManager in KafkaConfig.java is @Primary and
 *    couples DB and Kafka commits for exactly-once semantics.
 */
@Configuration
@Profile("local")
public class LocalKafkaConfig {

    @Value("${fraud.kafka.topics.transactions-raw}")
    private String transactionsRawTopic;

    @Value("${fraud.kafka.topics.transactions-flagged}")
    private String transactionsFlaggedTopic;

    @Value("${fraud.kafka.topics.transactions-passed}")
    private String transactionsPassedTopic;

    @Value("${fraud.kafka.topics.transactions-dlt}")
    private String transactionsDltTopic;

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    public NewTopic localTransactionsRawTopic() {
        return TopicBuilder.name(transactionsRawTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic localTransactionsFlaggedTopic() {
        return TopicBuilder.name(transactionsFlaggedTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic localTransactionsPassedTopic() {
        return TopicBuilder.name(transactionsPassedTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic localTransactionsDltTopic() {
        return TopicBuilder.name(transactionsDltTopic).partitions(1).replicas(1).build();
    }
}
