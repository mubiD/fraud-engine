package com.fraudengine.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// Routes every connection checkout to the reader inside a read-only transaction, and to
// the writer otherwise — no repository or service code needs to know this exists. Relies
// on Spring marking the current transaction read-only (TransactionSynchronizationManager)
// *before* Hibernate acquires its first physical JDBC connection for that transaction,
// which is why this only works cleanly with spring.jpa.open-in-view=false (already set —
// OSIV would otherwise open a connection outside any @Transactional boundary, before this
// routing decision is even meaningful).
public class ReplicationRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? DataSourceType.READER
                : DataSourceType.WRITER;
    }
}
