package com.fraudengine.streams;

// Thrown by RecentActivityStore implementations when the local Kafka Streams state
// store isn't queryable yet: app not RUNNING, or the store's partitions are still
// restoring from the changelog topic after a restart/rebalance. EvaluationContextBuilder
// catches this and falls back to the equivalent Postgres queries; it is not thrown for
// an empty-but-healthy result (a customer with no recent history), which is a normal
// Optional.empty() from lookup().
public class StoreUnavailableException extends RuntimeException {

    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public StoreUnavailableException(String message) {
        super(message);
    }
}
