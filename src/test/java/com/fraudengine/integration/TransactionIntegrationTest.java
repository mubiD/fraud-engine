package com.fraudengine.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudengine.api.dto.SubmitTransactionRequest;
import com.fraudengine.api.dto.SubmitTransactionResponse;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.repository.FraudAssessmentRepository;
import com.fraudengine.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class TransactionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired FraudAssessmentRepository assessmentRepository;
    @Autowired TransactionRepository transactionRepository;

    @BeforeEach
    void cleanUp() {
        assessmentRepository.deleteAll();
        transactionRepository.deleteAll();
    }

    @Test
    void submitTransaction_triggersAssessment_cleanTransaction() throws Exception {
        SubmitTransactionRequest request = SubmitTransactionRequest.builder()
                .customerId("CUST_001")
                .merchantId("CLEAN_MERCHANT")
                .amount(new BigDecimal("100.00"))
                .currency("GBP")
                .timestamp(Instant.now())
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        SubmitTransactionResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), SubmitTransactionResponse.class);
        UUID transactionId = response.getTransactionId();

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                assessmentRepository.findByTransactionId(transactionId).isPresent());

        Optional<FraudAssessment> assessment = assessmentRepository.findByTransactionId(transactionId);
        assertThat(assessment).isPresent();
        assertThat(assessment.get().isFraudulent()).isFalse();
        assertThat(assessment.get().getRiskScore()).isZero();
    }

    @Test
    void highAmountTransaction_flaggedAsFraudulent() throws Exception {
        SubmitTransactionRequest request = SubmitTransactionRequest.builder()
                .customerId("CUST_002")
                .merchantId("SOME_MERCHANT")
                .amount(new BigDecimal("10000.00"))
                .currency("GBP")
                .timestamp(Instant.now())
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID transactionId = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                SubmitTransactionResponse.class).getTransactionId();

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                assessmentRepository.findByTransactionId(transactionId).isPresent());

        FraudAssessment assessment = assessmentRepository.findByTransactionId(transactionId).orElseThrow();
        assertThat(assessment.isFraudulent()).isTrue();
        assertThat(assessment.getRiskScore()).isGreaterThanOrEqualTo(50);
    }

    @Test
    void blacklistedMerchant_flaggedAsFraudulent() throws Exception {
        SubmitTransactionRequest request = SubmitTransactionRequest.builder()
                .customerId("CUST_003")
                .merchantId("MERCHANT_FRAUD_001")
                .amount(new BigDecimal("50.00"))
                .currency("GBP")
                .timestamp(Instant.now())
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID transactionId = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                SubmitTransactionResponse.class).getTransactionId();

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                assessmentRepository.findByTransactionId(transactionId).isPresent());

        FraudAssessment assessment = assessmentRepository.findByTransactionId(transactionId).orElseThrow();
        assertThat(assessment.isFraudulent()).isTrue();
    }

    @Test
    void getAssessment_beforeProcessing_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/{id}/assessment", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidRequest_returns400() throws Exception {
        String badRequest = """
                { "customerId": "", "merchantId": "MERCH", "amount": -1, "currency": "GB" }
                """;
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badRequest))
                .andExpect(status().isBadRequest());
    }
}
