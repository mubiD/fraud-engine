package com.fraudengine.streams;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

import java.util.Optional;

// The only RecentActivityStore implementation — wraps Kafka Streams' Interactive Query
// API. Not registered under standalone/local (no StreamsBuilderFactoryBean bean exists
// there for the constructor to depend on), so EvaluationContextBuilder's
// Optional<RecentActivityStore> is empty under those profiles and it uses the Postgres
// path unconditionally, exactly as it did before this store existed.
@Component
@Profile("!standalone & !local")
public class KafkaStreamsRecentActivityStore implements RecentActivityStore {

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
            ReadOnlyKeyValueStore<String, CustomerActivityState> store = streams.store(
                    StoreQueryParameters.fromNameAndType(
                            CustomerActivityProcessor.STORE_NAME,
                            QueryableStoreTypes.keyValueStore()));
            return Optional.ofNullable(store.get(customerId));
        } catch (InvalidStateStoreException e) {
            // Not yet RUNNING, or this store's partitions are still restoring from the
            // changelog topic after a restart/rebalance — exactly the window
            // EvaluationContextBuilder's Postgres fallback exists for.
            throw new StoreUnavailableException("customer-activity-store not queryable", e);
        }
    }
}
