package com.fraudengine.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class TransactionId implements Serializable {

    private UUID id;
    private Instant timestamp;

    public TransactionId() {}

    public TransactionId(UUID id, Instant timestamp) {
        this.id = id;
        this.timestamp = timestamp;
    }

    public UUID getId() { return id; }
    public Instant getTimestamp() { return timestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransactionId that)) return false;
        return Objects.equals(id, that.id) && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, timestamp);
    }
}
