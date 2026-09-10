package com.fraudengine.streams;

import com.fraudengine.proto.TransactionEventProto;
import com.google.protobuf.Timestamp;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// TopologyTestDriver-based: pipes records through the real topology shape (source ->
// process -> state store) without a broker. Verifies CustomerActivityProcessor's
// append/prune behavior, which VelocityStreamsTopologyConfig wires identically in
// production, modulo the store type: this uses an in-memory store for test speed,
// while production uses Stores.persistentKeyValueStore for changelog-backed fault
// tolerance (see the implementation plan). The processor logic under test is
// unaffected either way.
class CustomerActivityProcessorTest {

    private static final String TOPIC = "transactions.raw";
    private static final Duration RECENT_WINDOW = Duration.ofMinutes(60);
    private static final int BASELINE_LOOKBACK_DAYS = 90;

    private TopologyTestDriver testDriver;
    private TestInputTopic<String, TransactionEventProto.TransactionEvent> inputTopic;

    @BeforeEach
    void setUp() {
        StreamsBuilder builder = new StreamsBuilder();
        StoreBuilder<KeyValueStore<String, CustomerActivityState>> storeBuilder = Stores.keyValueStoreBuilder(
                Stores.inMemoryKeyValueStore(CustomerActivityProcessor.STORE_NAME),
                Serdes.String(),
                new CustomerActivityStateSerde());
        builder.addStateStore(storeBuilder);

        builder.stream(TOPIC, Consumed.with(Serdes.String(), new TransactionEventSerde()))
                .process((ProcessorSupplier<String, TransactionEventProto.TransactionEvent, Void, Void>)
                                () -> new CustomerActivityProcessor(RECENT_WINDOW, BASELINE_LOOKBACK_DAYS),
                        CustomerActivityProcessor.STORE_NAME);

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "customer-activity-processor-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:0000");
        props.put(StreamsConfig.STATE_DIR_CONFIG, "target/kafka-streams-test/" + UUID.randomUUID());

        testDriver = new TopologyTestDriver(builder.build(), props);
        inputTopic = testDriver.createInputTopic(
                TOPIC, Serdes.String().serializer(), new TransactionEventSerde().serializer());
    }

    @AfterEach
    void tearDown() {
        if (testDriver != null) {
            testDriver.close();
        }
    }

    private CustomerActivityState stateFor(String customerId) {
        KeyValueStore<String, CustomerActivityState> store =
                testDriver.getKeyValueStore(CustomerActivityProcessor.STORE_NAME);
        return store.get(customerId);
    }

    @Test
    void appendsTransaction_toEmptyCustomerState() {
        Instant now = Instant.now();
        inputTopic.pipeInput("CUST_1", event("M1", "100.00", now));

        CustomerActivityState state = stateFor("CUST_1");
        assertThat(state.recentTransactions()).hasSize(1);
        assertThat(state.recentTransactions().get(0).merchantId()).isEqualTo("M1");
        assertThat(state.dailySpendTotal(now, "ZAR")).isEqualByComparingTo("100.00");
    }

    @Test
    void prunesRecentList_outsideWindow() {
        Instant old = Instant.now().minus(Duration.ofMinutes(90));
        Instant recent = Instant.now();

        inputTopic.pipeInput("CUST_1", event("M1", "50.00", old));
        inputTopic.pipeInput("CUST_1", event("M2", "75.00", recent));

        // Pruning is anchored on each write's own event timestamp (see
        // CustomerActivityState.withAppended). The second write's anchor (recent) prunes
        // the first (90 min prior, outside the 60-min window) out of the list.
        CustomerActivityState state = stateFor("CUST_1");
        assertThat(state.recentTransactions()).hasSize(1);
        assertThat(state.recentTransactions().get(0).merchantId()).isEqualTo("M2");
    }

    @Test
    void hourlyBucket_accumulatesWithinSameHour() {
        Instant t1 = Instant.parse("2026-01-01T10:15:00Z");
        Instant t2 = Instant.parse("2026-01-01T10:45:00Z");

        inputTopic.pipeInput("CUST_1", event("M1", "100.00", t1));
        inputTopic.pipeInput("CUST_1", event("M2", "50.00", t2));

        assertThat(stateFor("CUST_1").dailySpendTotal(t2, "ZAR")).isEqualByComparingTo("150.00");
    }

    @Test
    void hourlyBuckets_expireAfter24Hours() {
        Instant dayAgo = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = dayAgo.plus(Duration.ofHours(25));

        inputTopic.pipeInput("CUST_1", event("M1", "100.00", dayAgo));
        inputTopic.pipeInput("CUST_1", event("M2", "20.00", now));

        // The 25-hours-old bucket has aged out of the trailing 24-bucket window by the
        // time of the second write/read, so only the recent contribution remains.
        assertThat(stateFor("CUST_1").dailySpendTotal(now, "ZAR")).isEqualByComparingTo("20.00");
    }

    @Test
    void distinctCustomers_trackedIndependently() {
        Instant now = Instant.now();
        inputTopic.pipeInput("CUST_1", event("M1", "10.00", now));
        inputTopic.pipeInput("CUST_2", event("M1", "20.00", now));

        assertThat(stateFor("CUST_1").recentTransactions()).hasSize(1);
        assertThat(stateFor("CUST_2").recentTransactions()).hasSize(1);
    }

    @Test
    void dailyAmountBucket_accumulatesWithinSameDay() {
        Instant t1 = Instant.parse("2026-01-01T02:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T22:00:00Z");

        inputTopic.pipeInput("CUST_1", event("M1", "100.00", t1));
        inputTopic.pipeInput("CUST_1", event("M2", "50.00", t2));

        DailyAmountStats aggregate = stateFor("CUST_1").baselineAggregate(t2, BASELINE_LOOKBACK_DAYS, "ZAR");
        assertThat(aggregate.count()).isEqualTo(2);
        assertThat(aggregate.sum()).isEqualTo(150.0);
        assertThat(aggregate.sumOfSquares()).isEqualTo(100.0 * 100.0 + 50.0 * 50.0);
    }

    @Test
    void dailyAmountBuckets_expireAfterConfiguredLookback() {
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        Instant justInside = old.plus(Duration.ofDays(BASELINE_LOOKBACK_DAYS - 1));
        Instant justOutside = old.plus(Duration.ofDays(BASELINE_LOOKBACK_DAYS + 1));

        inputTopic.pipeInput("CUST_1", event("M1", "100.00", old));
        inputTopic.pipeInput("CUST_1", event("M2", "20.00", justInside));

        // Still within the lookback as of justInside's own write.
        assertThat(stateFor("CUST_1").baselineAggregate(justInside, BASELINE_LOOKBACK_DAYS, "ZAR").count()).isEqualTo(2);

        inputTopic.pipeInput("CUST_1", event("M3", "5.00", justOutside));

        // The oldest bucket has aged out of the lookback by the time of the third write.
        DailyAmountStats aggregate = stateFor("CUST_1").baselineAggregate(justOutside, BASELINE_LOOKBACK_DAYS, "ZAR");
        assertThat(aggregate.count()).isEqualTo(2);
        assertThat(aggregate.sum()).isEqualTo(25.0);
    }

    @Test
    void distinctCustomers_baselineTrackedIndependently() {
        Instant now = Instant.now();
        inputTopic.pipeInput("CUST_1", event("M1", "10.00", now));
        inputTopic.pipeInput("CUST_2", event("M1", "20.00", now));

        assertThat(stateFor("CUST_1").baselineAggregate(now, BASELINE_LOOKBACK_DAYS, "ZAR").sum()).isEqualTo(10.0);
        assertThat(stateFor("CUST_2").baselineAggregate(now, BASELINE_LOOKBACK_DAYS, "ZAR").sum()).isEqualTo(20.0);
    }

    @Test
    void differentCurrencies_hourlyAndDailyBuckets_trackedSeparately_notPooled() {
        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T10:05:00Z");

        inputTopic.pipeInput("CUST_1", event("M1", "100.00", "ZAR", t1));
        inputTopic.pipeInput("CUST_1", event("M2", "80.00", "USD", t2));

        CustomerActivityState state = stateFor("CUST_1");
        // Each currency's own bucket total, not a 180.00 pooled sum across currencies.
        assertThat(state.dailySpendTotal(t2, "ZAR")).isEqualByComparingTo("100.00");
        assertThat(state.dailySpendTotal(t2, "USD")).isEqualByComparingTo("80.00");

        DailyAmountStats zarBaseline = state.baselineAggregate(t2, BASELINE_LOOKBACK_DAYS, "ZAR");
        DailyAmountStats usdBaseline = state.baselineAggregate(t2, BASELINE_LOOKBACK_DAYS, "USD");
        assertThat(zarBaseline.count()).isEqualTo(1);
        assertThat(zarBaseline.sum()).isEqualTo(100.0);
        assertThat(usdBaseline.count()).isEqualTo(1);
        assertThat(usdBaseline.sum()).isEqualTo(80.0);
    }

    private TransactionEventProto.TransactionEvent event(String merchantId, String amount, Instant timestamp) {
        return event(merchantId, amount, "ZAR", timestamp);
    }

    private TransactionEventProto.TransactionEvent event(String merchantId, String amount, String currency,
                                                           Instant timestamp) {
        Timestamp ts = Timestamp.newBuilder()
                .setSeconds(timestamp.getEpochSecond()).setNanos(timestamp.getNano()).build();
        return TransactionEventProto.TransactionEvent.newBuilder()
                .setTransactionId(UUID.randomUUID().toString())
                .setCustomerId("unused-topology-keys-on-record-key-instead")
                .setMerchantId(merchantId)
                .setAmount(amount)
                .setCurrency(currency)
                .setCategory("RETAIL")
                .setTransactionType(TransactionEventProto.TransactionType.CARD_PRESENT)
                .setTimestamp(ts)
                .build();
    }

    // Minimal test-only Serde for the source topic: a raw protobuf byte round-trip, no
    // Confluent/Schema Registry dependency (unlike production's default.value.serde,
    // see application.yml, which is deliberately kept out of the default build's
    // compile-time dependencies).
    private static class TransactionEventSerde implements Serde<TransactionEventProto.TransactionEvent> {
        @Override
        public Serializer<TransactionEventProto.TransactionEvent> serializer() {
            return (topic, data) -> data == null ? null : data.toByteArray();
        }

        @Override
        public Deserializer<TransactionEventProto.TransactionEvent> deserializer() {
            return (topic, data) -> {
                if (data == null) {
                    return null;
                }
                try {
                    return TransactionEventProto.TransactionEvent.parseFrom(data);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
        }
    }
}
