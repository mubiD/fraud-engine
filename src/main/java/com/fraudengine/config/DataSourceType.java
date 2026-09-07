package com.fraudengine.config;

// Routing key for ReplicationRoutingDataSource. Two values only — this app has exactly
// one writer (the primary Postgres instance) and one reader (a replica, or the same
// instance when no real replica is configured — see DataSourceConfig).
public enum DataSourceType {
    WRITER,
    READER
}
