package com.fraudengine.streams;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.TaskMetadata;
import org.apache.kafka.streams.ThreadMetadata;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

// Covers the fix for a real silent-correctness gap: lookup() used to return
// Optional.empty() for a key hosted on a *different* Kafka Streams instance, which
// EvaluationContextBuilder reads identically to "no recent history". That's worse than
// the documented staleness tradeoff elsewhere, because it's silent rather than a handled
// fallback. These tests exercise the queryMetadataForKey/metadataForLocalThreads guard
// added to close that gap.
@ExtendWith(MockitoExtension.class)
class KafkaStreamsRecentActivityStoreTest {

    private static final String CUSTOMER_ID = "CUST_01";
    private static final String STORE_NAME = CustomerActivityProcessor.STORE_NAME;
    private static final String SOURCE_TOPIC = "transactions.raw";

    @Mock StreamsBuilderFactoryBean factoryBean;
    @Mock KafkaStreams streams;
    @Mock ReadOnlyKeyValueStore<String, CustomerActivityState> store;
    @Mock ThreadMetadata threadMetadata;
    @Mock TaskMetadata taskMetadata;

    @Test
    void streamsNotStarted_throwsStoreUnavailable() {
        when(factoryBean.getKafkaStreams()).thenReturn(null);
        KafkaStreamsRecentActivityStore recentActivityStore = new KafkaStreamsRecentActivityStore(factoryBean);

        assertThatThrownBy(() -> recentActivityStore.lookup(CUSTOMER_ID))
                .isInstanceOf(StoreUnavailableException.class);
    }

    @Test
    void partitionMetadataNotYetAvailable_throwsStoreUnavailable() {
        when(factoryBean.getKafkaStreams()).thenReturn(streams);
        when(streams.queryMetadataForKey(eq(STORE_NAME), eq(CUSTOMER_ID), any(StringSerializer.class)))
                .thenReturn(KeyQueryMetadata.NOT_AVAILABLE);

        KafkaStreamsRecentActivityStore recentActivityStore = new KafkaStreamsRecentActivityStore(factoryBean);

        assertThatThrownBy(() -> recentActivityStore.lookup(CUSTOMER_ID))
                .isInstanceOf(StoreUnavailableException.class);
    }

    @Test
    void partitionHostedOnAnotherInstance_throwsStoreUnavailable_insteadOfSilentEmpty() {
        when(factoryBean.getKafkaStreams()).thenReturn(streams);
        KeyQueryMetadata metadata = new KeyQueryMetadata(HostInfo.buildFromEndpoint("other-host:1234"), Set.of(), 3);
        when(streams.queryMetadataForKey(eq(STORE_NAME), eq(CUSTOMER_ID), any(StringSerializer.class)))
                .thenReturn(metadata);
        when(streams.metadataForLocalThreads()).thenReturn(Set.of(threadMetadata));
        when(threadMetadata.activeTasks()).thenReturn(Set.of(taskMetadata));
        // This instance only hosts partition 7; the key's partition (3) belongs elsewhere.
        when(taskMetadata.topicPartitions()).thenReturn(Set.of(new TopicPartition(SOURCE_TOPIC, 7)));

        KafkaStreamsRecentActivityStore recentActivityStore = new KafkaStreamsRecentActivityStore(factoryBean);

        assertThatThrownBy(() -> recentActivityStore.lookup(CUSTOMER_ID))
                .isInstanceOf(StoreUnavailableException.class)
                .hasMessageContaining("not hosted");
    }

    @Test
    void partitionHostedLocally_returnsStoreResult() {
        when(factoryBean.getKafkaStreams()).thenReturn(streams);
        KeyQueryMetadata metadata = new KeyQueryMetadata(HostInfo.buildFromEndpoint("this-host:1234"), Set.of(), 3);
        when(streams.queryMetadataForKey(eq(STORE_NAME), eq(CUSTOMER_ID), any(StringSerializer.class)))
                .thenReturn(metadata);
        when(streams.metadataForLocalThreads()).thenReturn(Set.of(threadMetadata));
        when(threadMetadata.activeTasks()).thenReturn(Set.of(taskMetadata));
        when(taskMetadata.topicPartitions()).thenReturn(Set.of(new TopicPartition(SOURCE_TOPIC, 3)));

        CustomerActivityState expected = CustomerActivityState.empty();
        when(streams.<ReadOnlyKeyValueStore<String, CustomerActivityState>>store(any(StoreQueryParameters.class)))
                .thenReturn(store);
        when(store.get(CUSTOMER_ID)).thenReturn(expected);

        KafkaStreamsRecentActivityStore recentActivityStore = new KafkaStreamsRecentActivityStore(factoryBean);

        Optional<CustomerActivityState> result = recentActivityStore.lookup(CUSTOMER_ID);

        assertThat(result).contains(expected);
    }

    @Test
    void storeStillRestoring_wrapsAsStoreUnavailable() {
        when(factoryBean.getKafkaStreams()).thenReturn(streams);
        KeyQueryMetadata metadata = new KeyQueryMetadata(HostInfo.buildFromEndpoint("this-host:1234"), Set.of(), 3);
        when(streams.queryMetadataForKey(eq(STORE_NAME), eq(CUSTOMER_ID), any(StringSerializer.class)))
                .thenReturn(metadata);
        when(streams.metadataForLocalThreads()).thenReturn(Set.of(threadMetadata));
        when(threadMetadata.activeTasks()).thenReturn(Set.of(taskMetadata));
        when(taskMetadata.topicPartitions()).thenReturn(Set.of(new TopicPartition(SOURCE_TOPIC, 3)));
        when(streams.<ReadOnlyKeyValueStore<String, CustomerActivityState>>store(any(StoreQueryParameters.class)))
                .thenThrow(new InvalidStateStoreException("restoring"));

        KafkaStreamsRecentActivityStore recentActivityStore = new KafkaStreamsRecentActivityStore(factoryBean);

        assertThatThrownBy(() -> recentActivityStore.lookup(CUSTOMER_ID))
                .isInstanceOf(StoreUnavailableException.class);
    }
}
