package com.fraudengine.streams;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.proto.TransactionEventProto;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafkaStreams;

import java.time.Duration;

// PRODUCTION ONLY: mirrors KafkaConfig / TransactionConsumer / AssessmentProducer's exact
// profile scoping. `local` never runs real Kafka ingestion either (TransactionConsumer
// doesn't activate under it, see application-local.yml), so this topology has no reason
// to run there. `standalone` has no Kafka at all.
//
// Builds "customer-activity-store": a second, independent consumer group on
// transactions.raw whose sole job is materialising each customer's recent-activity state
// for EvaluationContextBuilder to read via KafkaStreamsRecentActivityStore, in place of
// its live Postgres queries (findRecentByCustomer / sumAmountByCustomerSince). See the
// implementation plan and DESIGN.md §5 for the full rationale, including why this is a
// custom Processor API store rather than native SlidingWindows/TimeWindows KTables.
//
// The source Serde is deliberately configured via spring.kafka.streams.properties
// (default.value.serde = Confluent's protobuf Serde, as a string class name), not a
// compile-time import: this project keeps Confluent's Schema-Registry-aware classes out
// of the default (non -Pconfluent) build entirely, the same way KafkaConfig's producer/
// consumer serializers are wired purely through application.yml property strings.
@Configuration
@EnableKafkaStreams
@Profile("!standalone & !local")
public class VelocityStreamsTopologyConfig {

    @Value("${fraud.kafka.topics.transactions-raw}")
    private String transactionsRawTopic;

    private final RuleProperties ruleProperties;

    public VelocityStreamsTopologyConfig(RuleProperties ruleProperties) {
        this.ruleProperties = ruleProperties;
    }

    @Bean
    public KStream<String, TransactionEventProto.TransactionEvent> customerActivityTopology(
            StreamsBuilder streamsBuilder) {

        StoreBuilder<KeyValueStore<String, CustomerActivityState>> storeBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(CustomerActivityProcessor.STORE_NAME),
                        Serdes.String(),
                        new CustomerActivityStateSerde());
        streamsBuilder.addStateStore(storeBuilder);

        Duration recentWindow = Duration.ofMinutes(ruleProperties.getContextLookbackMinutes());
        int baselineLookbackDays = ruleProperties.getCustomerAmountAnomaly().getLookbackDays();

        KStream<String, TransactionEventProto.TransactionEvent> stream =
                streamsBuilder.stream(transactionsRawTopic);

        stream.process(
                (ProcessorSupplier<String, TransactionEventProto.TransactionEvent, Void, Void>)
                        () -> new CustomerActivityProcessor(recentWindow, baselineLookbackDays),
                CustomerActivityProcessor.STORE_NAME);

        return stream;
    }
}
