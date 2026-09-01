package com.fraudengine.integration;

import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Disposition;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.proto.TransactionEventProto;
import com.fraudengine.repository.FraudAssessmentRepository;
import com.fraudengine.repository.TransactionRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class TransactionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired MockMvc mockMvc;
    @Autowired FraudAssessmentRepository assessmentRepository;
    @Autowired TransactionRepository transactionRepository;

    @Value("${fraud.kafka.topics.transactions-raw}")
    String rawTopic;

    // mock:// schema registry is an in-process singleton — same URL = same registry instance
    private static final String MOCK_SCHEMA_REGISTRY = "mock://fraud-engine-test";

    KafkaTemplate<String, TransactionEventProto.TransactionEvent> testTemplate;

    @BeforeEach
    void setUp() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // PRODUCTION: KafkaProtobufSerializer (io.confluent) is the production wire format.
        // Referenced as a string so the test compiles without the confluent Maven profile.
        // Run full integration tests with: mvn verify -Pconfluent
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer");
        props.put("schema.registry.url", MOCK_SCHEMA_REGISTRY);
        testTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));

        assessmentRepository.deleteAll();
        transactionRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Kafka → assessment → passed topic
    // -----------------------------------------------------------------------

    @Test
    void cleanTransaction_isAssessedAndPublishedToPassedTopic() {
        UUID txId = UUID.randomUUID();
        TransactionEventProto.TransactionEvent event = buildEvent(txId, "CUST_001", "CLEAN_MERCH",
                new BigDecimal("100.00"), TransactionType.CARD_PRESENT);

        try (KafkaConsumer<String, byte[]> consumer = openConsumer("transactions.passed")) {
            consumer.poll(Duration.ofMillis(300)); // position at current end

            testTemplate.send(rawTopic, event.getCustomerId(), event);

            await().atMost(10, TimeUnit.SECONDS).until(() ->
                    assessmentRepository.findByTransactionId(txId).isPresent());

            FraudAssessment assessment = assessmentRepository.findByTransactionId(txId).orElseThrow();
            assertThat(assessment.getDisposition()).isEqualTo(Disposition.CLEARED);
            assertThat(assessment.getRiskScore()).isZero();

            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofSeconds(5));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);
        }
    }

    // -----------------------------------------------------------------------
    // Kafka → assessment → flagged topic
    // -----------------------------------------------------------------------

    @Test
    void highAmountTransaction_isFlaggedAndPublishedToFlaggedTopic() {
        UUID txId = UUID.randomUUID();
        TransactionEventProto.TransactionEvent event = buildEvent(txId, "CUST_002", "SOME_MERCH",
                new BigDecimal("10000.00"), TransactionType.CARD_NOT_PRESENT);

        try (KafkaConsumer<String, byte[]> consumer = openConsumer("transactions.flagged")) {
            consumer.poll(Duration.ofMillis(300));

            testTemplate.send(rawTopic, event.getCustomerId(), event);

            await().atMost(10, TimeUnit.SECONDS).until(() ->
                    assessmentRepository.findByTransactionId(txId).isPresent());

            FraudAssessment assessment = assessmentRepository.findByTransactionId(txId).orElseThrow();
            assertThat(assessment.getDisposition()).isEqualTo(Disposition.FLAGGED);
            assertThat(assessment.getRiskScore()).isGreaterThanOrEqualTo(50);

            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofSeconds(5));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void blacklistedMerchant_isFlagged() {
        UUID txId = UUID.randomUUID();
        TransactionEventProto.TransactionEvent event = buildEvent(txId, "CUST_003", "MERCHANT_FRAUD_001",
                new BigDecimal("50.00"), TransactionType.CARD_PRESENT);

        testTemplate.send(rawTopic, event.getCustomerId(), event);

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                assessmentRepository.findByTransactionId(txId).isPresent());

        assertThat(assessmentRepository.findByTransactionId(txId).orElseThrow().getDisposition())
                .isEqualTo(Disposition.FLAGGED);
    }

    // -----------------------------------------------------------------------
    // Query API
    // -----------------------------------------------------------------------

    @Test
    void getByCustomerId_returnsBothPendingAndAssessedTransactions() throws Exception {
        String customerId = "CUST_QUERY";

        Transaction pending = Transaction.builder()
                .customerId(customerId)
                .merchantId("MERCH_A")
                .amount(new BigDecimal("25.00"))
                .currency("GBP")
                .timestamp(Instant.now().minusSeconds(120))
                .transactionType(TransactionType.CARD_PRESENT)
                .build();
        transactionRepository.save(pending);

        UUID kafkaTxId = UUID.randomUUID();
        TransactionEventProto.TransactionEvent event = buildEvent(kafkaTxId, customerId, "MERCH_B",
                new BigDecimal("75.00"), TransactionType.CARD_NOT_PRESENT);
        testTemplate.send(rawTopic, customerId, event);

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                assessmentRepository.findByTransactionId(kafkaTxId).isPresent());

        mockMvc.perform(get("/api/v1/transactions").param("customerId", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].status", hasItems("PENDING", "ASSESSED")));
    }

    @Test
    void getAssessment_nonExistentTransaction_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/{id}/assessment", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private TransactionEventProto.TransactionEvent buildEvent(UUID id, String customerId,
                                                               String merchantId, BigDecimal amount,
                                                               TransactionType type) {
        TransactionEventProto.TransactionType protoType = switch (type) {
            case CARD_PRESENT  -> TransactionEventProto.TransactionType.CARD_PRESENT;
            case CONTACTLESS   -> TransactionEventProto.TransactionType.CONTACTLESS;
            case ATM           -> TransactionEventProto.TransactionType.ATM;
            default            -> TransactionEventProto.TransactionType.CARD_NOT_PRESENT;
        };

        Instant now = Instant.now();
        return TransactionEventProto.TransactionEvent.newBuilder()
                .setTransactionId(id.toString())
                .setCustomerId(customerId)
                .setMerchantId(merchantId)
                .setAmount(amount.toPlainString())
                .setCurrency("GBP")
                .setTransactionType(protoType)
                .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(now.getEpochSecond())
                        .setNanos(now.getNano())
                        .build())
                .build();
    }

    private KafkaConsumer<String, byte[]> openConsumer(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG,
                "test-" + topic.replace(".", "-") + "-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(topic));
        return consumer;
    }
}
