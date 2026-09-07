package com.fraudengine.streams;

import com.fraudengine.model.Transaction;
import com.fraudengine.proto.ProtoMapper;
import com.fraudengine.proto.TransactionEventProto;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Duration;

// Sole writer of "customer-activity-store". Runs inside its own Kafka Streams
// application (see VelocityStreamsTopologyConfig) — an independent consumer group on
// transactions.raw, deliberately not wired into TransactionConsumer's exactly-once
// transaction: this processor's only job is materialising read-side state for
// EvaluationContextBuilder, not participating in assessment persistence. Per-customer
// ordering is preserved the same way it is for TransactionConsumer — transactions.raw is
// partitioned by customerId — but the two consumer groups are not coordinated with each
// other, so this store can lag behind what TransactionConsumer has already processed
// (see the plan's Known Limitations: an accepted, monitored staleness risk, not solved
// here). Terminal processor: nothing is forwarded downstream, it only writes state.
public class CustomerActivityProcessor
        implements Processor<String, TransactionEventProto.TransactionEvent, Void, Void> {

    public static final String STORE_NAME = "customer-activity-store";

    private final Duration recentWindow;
    private KeyValueStore<String, CustomerActivityState> store;

    public CustomerActivityProcessor(Duration recentWindow) {
        this.recentWindow = recentWindow;
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

        Transaction tx = ProtoMapper.toTransactionEntity(record.value());
        RecentTransactionRecord entry = new RecentTransactionRecord(
                tx.getId(), tx.getMerchantId(), tx.getAmount(), tx.getCurrency(),
                tx.getCategory(), tx.getTransactionType(), tx.getTimestamp(),
                tx.getLatitude(), tx.getLongitude());

        CustomerActivityState current = store.get(customerId);
        if (current == null) {
            current = CustomerActivityState.empty();
        }
        store.put(customerId, current.withAppended(entry, recentWindow));
    }
}
