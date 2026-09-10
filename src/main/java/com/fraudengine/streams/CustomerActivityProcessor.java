package com.fraudengine.streams;

import com.fraudengine.model.Transaction;
import com.fraudengine.proto.ProtoMapper;
import com.fraudengine.proto.TransactionEventProto;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

// Sole writer of "customer-activity-store". Runs inside its own Kafka Streams
// application (see VelocityStreamsTopologyConfig), an independent consumer group on
// transactions.raw, deliberately not wired into TransactionConsumer's exactly-once
// transaction: this processor's only job is materialising read-side state for
// EvaluationContextBuilder, not participating in assessment persistence. Per-customer
// ordering is preserved the same way it is for TransactionConsumer: transactions.raw is
// partitioned by customerId, but the two consumer groups are not coordinated with each
// other, so this store can lag behind what TransactionConsumer has already processed
// (see the plan's Known Limitations: an accepted, monitored staleness risk, not solved
// here). Terminal processor: nothing is forwarded downstream, it only writes state.
public class CustomerActivityProcessor
        implements Processor<String, TransactionEventProto.TransactionEvent, Void, Void> {

    private static final Logger log = LoggerFactory.getLogger(CustomerActivityProcessor.class);

    public static final String STORE_NAME = "customer-activity-store";

    private final Duration recentWindow;
    private final int baselineLookbackDays;
    private KeyValueStore<String, CustomerActivityState> store;

    public CustomerActivityProcessor(Duration recentWindow, int baselineLookbackDays) {
        this.recentWindow = recentWindow;
        this.baselineLookbackDays = baselineLookbackDays;
    }

    @Override
    public void init(ProcessorContext<Void, Void> context) {
        this.store = context.getStateStore(STORE_NAME);
    }

    @Override
    public void process(Record<String, TransactionEventProto.TransactionEvent> record) {
        String customerId = record.key();
        if (customerId == null || record.value() == null) {
            return;
        }

        // Deliberately broad catch: this store is a best-effort read-side cache with an
        // existing, always-available fallback (EvaluationContextBuilder falls back to
        // Postgres on StoreUnavailableException), so one record this processor can't handle
        // — a mapping bug, an unexpected proto shape that deserialized fine but is
        // semantically malformed, anything not already caught by the deserialization-exception
        // handler in application.yml — should cost this one customer's freshness for this one
        // update, not crash the stream thread. An uncaught exception here has no retry-topic/
        // DLT to land in (that machinery is TransactionConsumer's, not this Processor API
        // topology's) and would otherwise crash-loop this partition indefinitely: Kafka
        // Streams' default REPLACE_THREAD behaviour respawns the thread, which immediately
        // re-reads the same un-committed offset and fails again.
        try {
            Transaction tx = ProtoMapper.toTransactionEntity(record.value());
            RecentTransactionRecord entry = new RecentTransactionRecord(
                    tx.getId(), tx.getMerchantId(), tx.getAmount(), tx.getCurrency(),
                    tx.getCategory(), tx.getTransactionType(), tx.getTimestamp(),
                    tx.getLatitude(), tx.getLongitude(), tx.getDeviceFingerprint());

            CustomerActivityState current = store.get(customerId);
            if (current == null) {
                current = CustomerActivityState.empty();
            }
            store.put(customerId, current.withAppended(entry, recentWindow, baselineLookbackDays));
        } catch (Exception e) {
            log.error("Failed to update customer-activity-store for customerId={}, offset={} — "
                    + "skipping this record; EvaluationContextBuilder's Postgres fallback covers "
                    + "reads until the next successful update for this customer",
                    customerId, record.timestamp(), e);
        }
    }
}
