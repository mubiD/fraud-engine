package com.fraudengine.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicationRoutingDataSourceTest {

    private final ReplicationRoutingDataSource routingDataSource = new ReplicationRoutingDataSource();

    @AfterEach
    void tearDown() {
        // TransactionSynchronizationManager state is thread-bound, not instance-bound —
        // clear it so a failed assertion here can't leak read-only status into another test.
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test
    void readOnlyTransaction_routesToReader() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThat(routingDataSource.determineCurrentLookupKey()).isEqualTo(DataSourceType.READER);
    }

    @Test
    void writeTransaction_routesToWriter() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

        assertThat(routingDataSource.determineCurrentLookupKey()).isEqualTo(DataSourceType.WRITER);
    }

    @Test
    void noActiveTransaction_defaultsToWriter() {
        // isCurrentTransactionReadOnly() is false outside any transaction — same as a
        // plain write, which is the safer default when nothing has said otherwise.
        assertThat(routingDataSource.determineCurrentLookupKey()).isEqualTo(DataSourceType.WRITER);
    }
}
