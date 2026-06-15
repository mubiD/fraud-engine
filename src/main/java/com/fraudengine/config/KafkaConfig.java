package com.fraudengine.config;

import com.fraudengine.kafka.TransactionEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaConfig {

    @Value("${fraud.kafka.topics.transactions-raw}")
    private String transactionsRawTopic;

    @Value("${fraud.kafka.topics.transactions-flagged}")
    private String transactionsFlaggedTopic;

    @Value("${fraud.kafka.topics.transactions-dlt}")
    private String transactionsDltTopic;

    @Bean
    public NewTopic transactionsRawTopic() {
        return TopicBuilder.name(transactionsRawTopic).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic transactionsFlaggedTopic() {
        return TopicBuilder.name(transactionsFlaggedTopic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic transactionsDltTopic() {
        return TopicBuilder.name(transactionsDltTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public KafkaTemplate<String, TransactionEvent> kafkaTemplate(
            ProducerFactory<String, TransactionEvent> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
