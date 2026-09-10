package com.fraudengine.streams;

import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

import java.util.Optional;

// The only RecentActivityStore implementation, wrapping Kafka Streams' Interactive Query
// API. Not registered under standalone/local (no StreamsBuilderFactoryBean bean exists
// there for the constructor to depend on), so EvaluationContextBuilder's
// Optional<RecentActivityStore> is empty under those profiles and it uses the Postgres
// path unconditionally, exactly as it did before this store existed.
@Component
@Profile("!standalone & !local")
public class KafkaStreamsRecentActivityStore implements RecentActivityStore {

    private static final StringSerializer KEY_SERIALIZER = new StringSerializer();

    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;

    public KafkaStreamsRecentActivityStore(StreamsBuilderFactoryBean streamsBuilderFactoryBean) {
        this.streamsBuilderFactoryBean = streamsBuilderFactoryBean;
    }

    @Override
    public Optional<CustomerActivityState> lookup(String customerId) {
        KafkaStreams streams = streamsBuilderFactoryBean.getKafkaStreams();
        if (streams == null) {
            throw new StoreUnavailableException("Kafka Streams application not started");
        }
        try {
            assertPartitionHostedLocally(streams, customerId);
            ReadOnlyKeyValueStore<String, CustomerActivityState> store = streams.store(
                    StoreQueryParameters.fromNameAndType(
                            CustomerActivityProcessor.STORE_NAME,
                            QueryableStoreTypes.keyValueStore()));
            return Optional.ofNullable(store.get(customerId));
        } catch (InvalidStateStoreException e) {
            // Not yet RUNNING, or this store's partitions are still restoring from the
            // changelog topic after a restart/rebalance: exactly the window
            // EvaluationContextBuilder's Postgres fallback exists for.
            throw new StoreUnavailableException("customer-activity-store not queryable", e);
        }
    }

    // Interactive Query only guarantees a correct answer when queried against the instance
    // that actually owns the key's partition (KIP-535): a local ReadOnlyKeyValueStore.get()
    // for a key hosted on a *different* instance doesn't throw, it just returns null, which
    // looks identical to "this customer genuinely has no recent history." Found live
    // 2026-09-09: this app runs a single Kafka Streams instance in practice
    // (VelocityStreamsTopologyConfig's own docs), so this check is normally a no-op, but it
    // turns "scaled to >1 replica without also building remote-store routing" from a silent
    // wrong answer (velocity rules reading empty state as clean history) into a loud, already
    // -handled fallback to Postgres. See EvaluationContextBuilder.loadRecentActivity. Real
    // horizontal scaling would still want an RPC layer (application.server + an internal
    // endpoint peers can call) to route to the owning instance instead of merely falling
    // back; not built here, tracked as a known limitation, not solved.
    private void assertPartitionHostedLocally(KafkaStreams streams, String customerId) {
        KeyQueryMetadata metadata = streams.queryMetadataForKey(
                CustomerActivityProcessor.STORE_NAME, customerId, KEY_SERIALIZER);
        if (metadata == null || metadata == KeyQueryMetadata.NOT_AVAILABLE) {
            throw new StoreUnavailableException("customer-activity-store partition metadata not yet available");
        }

        boolean hostedLocally = streams.metadataForLocalThreads().stream()
                .flatMap(thread -> thread.activeTasks().stream())
                .flatMap(task -> task.topicPartitions().stream())
                .anyMatch(tp -> tp.partition() == metadata.partition());

        if (!hostedLocally) {
            throw new StoreUnavailableException(
                    "customer-activity-store partition " + metadata.partition()
                            + " for this key is not hosted on this instance");
        }
    }
}
