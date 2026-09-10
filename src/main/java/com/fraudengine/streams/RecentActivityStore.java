package com.fraudengine.streams;

import java.util.Optional;

// Port EvaluationContextBuilder depends on instead of talking to Kafka Streams'
// ReadOnlyKeyValueStore/Interactive Query APIs directly. The only implementation is
// KafkaStreamsRecentActivityStore, registered only under the production Kafka profile
// (!standalone & !local). EvaluationContextBuilder receives this as
// Optional<RecentActivityStore> and falls back to Postgres whenever it's empty or a
// lookup throws StoreUnavailableException.
public interface RecentActivityStore {

    // Returns the customer's current materialised state, or empty if the customer has
    // no recorded activity yet (a normal, healthy outcome, not the same as the store
    // being unavailable).
    Optional<CustomerActivityState> lookup(String customerId);
}
