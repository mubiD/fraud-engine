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
// production (modulo the store type — this uses an in-memory store for test speed;
// production uses Stores.persistentKeyValueStore for changelog-backed fault tolerance,
// see the implementation plan — the processor logic under test is unaffected either way).
class CustomerActivityProcessorTest {

    private static final String TOPIC = "transactions.raw";
    private static final Duration RECENT_WINDOW = Duration.ofMinutes(60);

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
                                () -> new CustomerActivityProcessor(RECENT_WINDOW),
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
        assertThat(state.dailySpendTotal(now)).isEqualByComparingTo("100.00");
    }

    @Test
    void prunesRecentList_outsideWindow() {
        Instant old = Instant.now().minus(Duration.ofMinutes(90));
        Instant recent = Instant.now();

        inputTopic.pipeInput("CUST_1", event("M1", "50.00", old));
        inputTopic.pipeInput("CUST_1", event("M2", "75.00", recent));

        // Pruning is anchored on each write's own event timestamp (see
        // CustomerActivityState.withAppended) — the second write's anchor (recent) prunes
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

        assertThat(stateFor("CUST_1").dailySpendTotal(t2)).isEqualByComparingTo("150.00");
    }

    @Test
    void hourlyBuckets_expireAfter24Hours() {
        Instant dayAgo = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = dayAgo.plus(Duration.ofHours(25));

        inputTopic.pipeInput("CUST_1", event("M1", "100.00", dayAgo));
        inputTopic.pipeInput("CUST_1", event("M2", "20.00", now));

        // The 25-hours-old bucket has aged out of the trailing 24-bucket window by the
        // time of the second write/read — only the recent contribution remains.
        assertThat(stateFor("CUST_1").dailySpendTotal(now)).isEqualByComparingTo("20.00");
    }

    @Test
    void distinctCustomers_trackedIndependently() {
        Instant now = Instant.now();
        inputTopic.pipeInput("CUST_1", event("M1", "10.00", now));
        inputTopic.pipeInput("CUST_2", event("M1", "20.00", now));

        assertThat(stateFor("CUST_1").recentTransactions()).hasSize(1);
        assertThat(stateFor("CUST_2").recentTransactions()).hasSize(1);
    }

    private TransactionEventProto.TransactionEvent event(String merchantId, String amount, Instant timestamp) {
        Timestamp ts = Timestamp.newBuilder()
                .setSeconds(timestamp.getEpochSecond()).setNanos(timestamp.getNano()).build();
        return TransactionEventProto.TransactionEvent.newBuilder()
                .setTransactionId(UUID.randomUUID().toString())
                .setCustomerId("unused-topology-keys-on-record-key-instead")
                .setMerchantId(merchantId)
                .setAmount(amount)
                .setCurrency("ZAR")
                .setCategory("RETAIL")
                .setTransactionType(TransactionEventProto.TransactionType.CARD_PRESENT)
                .setTimestamp(ts)
                .build();
    }

    // Minimal test-only Serde for the source topic — a raw protobuf byte round-trip, no
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
