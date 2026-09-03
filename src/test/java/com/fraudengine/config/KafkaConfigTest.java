package com.fraudengine.config;

import com.fraudengine.consumer.TransactionConsumer;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Kafka's retry-topic publisher preserves the original record's partition index across
 * every retry hop and the final DLT publish by default, and throws if the destination topic
 * doesn't have that many partitions. These tests guard against the partition counts drifting
 * back out of sync with each other — see TransactionConsumer's @RetryableTopic and
 * KafkaConfig.transactionsDltTopic() for the full explanation.
 */
class KafkaConfigTest {

    KafkaConfig config;

    @BeforeEach
    void setUp() {
        config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "transactionsRawTopic", "transactions.raw");
        ReflectionTestUtils.setField(config, "transactionsDltTopic", "transactions.raw.DLT");
        ReflectionTestUtils.setField(config, "replicationFactor", 1);
    }

    @Test
    void dltTopic_hasAtLeastAsManyPartitionsAsRawTopic() {
        NewTopic raw = config.transactionsRawTopic();
        NewTopic dlt = config.transactionsDltTopic();

        assertThat(dlt.numPartitions()).isGreaterThanOrEqualTo(raw.numPartitions());
    }

    @Test
    void retryableTopic_numPartitions_matchesRawTopicPartitionCount() throws NoSuchMethodException {
        NewTopic raw = config.transactionsRawTopic();

        Method consume = TransactionConsumer.class.getMethod(
                "consume", com.fraudengine.proto.TransactionEventProto.TransactionEvent.class,
                String.class, int.class, long.class);
        RetryableTopic annotation = consume.getAnnotation(RetryableTopic.class);

        assertThat(annotation).isNotNull();
        assertThat(Integer.parseInt(annotation.numPartitions())).isEqualTo(raw.numPartitions());
    }
}
