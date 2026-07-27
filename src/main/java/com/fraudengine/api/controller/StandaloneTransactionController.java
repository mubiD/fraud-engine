package com.fraudengine.api.controller;

import com.fraudengine.api.dto.FraudAssessmentDto;
import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.engine.RuleEngine;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.repository.FraudAssessmentRepository;
import com.fraudengine.repository.TransactionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * STUB — active under the standalone and local Spring profiles.
 *
 * In production, transactions enter the system via the transactions.raw Kafka topic,
 * which TransactionConsumer.consume() processes asynchronously under a
 * ChainedKafkaTransactionManager (exactly-once guarantee). Assessment results are then
 * published to transactions.flagged or transactions.passed for downstream consumers.
 *
 * This endpoint replicates that flow synchronously so the rule engine can be exercised
 * without a full Kafka pipeline:
 *   standalone — in-memory H2, no Kafka, suitable for quick demos.
 *   local      — real PostgreSQL + Kafka (docker-compose), JSON wire format.
 */
@RestController
@RequestMapping("/api/v1/standalone")
@Profile("standalone | local")
@Validated
@Tag(
    name = "Standalone Demo",
    description = """
        STUB — present when the standalone or local profile is active.
        Submits a transaction directly to the rule engine, bypassing the Kafka consumer.
        In production this flow is driven by the transactions.raw Kafka topic
        (TransactionConsumer → RuleEngine → AssessmentProducer).
        """
)
public class StandaloneTransactionController {

    private final TransactionRepository transactionRepository;
    private final FraudAssessmentRepository fraudAssessmentRepository;
    private final RuleEngine ruleEngine;
    private final TransactionMapper mapper;

    public StandaloneTransactionController(TransactionRepository transactionRepository,
                                           FraudAssessmentRepository fraudAssessmentRepository,
                                           RuleEngine ruleEngine,
                                           TransactionMapper mapper) {
        this.transactionRepository = transactionRepository;
        this.fraudAssessmentRepository = fraudAssessmentRepository;
        this.ruleEngine = ruleEngine;
        this.mapper = mapper;
    }

    @PostMapping("/submit")
    @Transactional
    @Operation(
        summary = "Submit a transaction for fraud assessment (standalone demo)",
        description = """
            STUB: Persists the transaction, runs it through the fraud rule engine, and returns the assessment.
            In production this path is replaced by the Kafka consumer pipeline
            (TransactionConsumer → RuleEngine → AssessmentProducer).
            """
    )
    @ApiResponse(responseCode = "200", description = "Assessment completed")
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    public ResponseEntity<FraudAssessmentDto> submit(@RequestBody @Valid TransactionRequest request) {
        Transaction tx = Transaction.builder()
                .customerId(request.customerId())
                .merchantId(request.merchantId())
                .amount(request.amount())
                .currency(request.currency())
                .category(request.category())
                .location(request.location())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .timestamp(Instant.now())
                .transactionType(request.transactionType() != null
                        ? request.transactionType()
                        : TransactionType.CARD_NOT_PRESENT)
                .status(TransactionStatus.PENDING)
                .build();

        tx = transactionRepository.save(tx);

        FraudAssessment assessment = ruleEngine.evaluate(tx);
        fraudAssessmentRepository.save(assessment);

        tx.setStatus(TransactionStatus.ASSESSED);
        transactionRepository.save(tx);

        return ResponseEntity.ok(mapper.toDto(assessment));
    }

    @Schema(description = "Transaction to submit for fraud assessment")
    record TransactionRequest(

        @Schema(description = "Customer identifier", example = "CUST-001")
        @NotBlank String customerId,

        @Schema(description = "Merchant identifier — use MERCHANT_FRAUD_001/002/003 to trigger the blacklist rule",
                example = "MERCH-NIKE-ZA")
        @NotBlank String merchantId,

        @Schema(description = "Transaction amount — values above 5000 trigger the AmountThresholdRule",
                example = "6500.00")
        @NotNull @Positive BigDecimal amount,

        @Schema(description = "ISO 4217 currency code", example = "ZAR")
        @NotBlank @Size(min = 3, max = 3) String currency,

        @Schema(description = "Merchant category", example = "RETAIL")
        String category,

        @Schema(description = "Transaction channel — CARD_PRESENT, CARD_NOT_PRESENT, CONTACTLESS, ATM")
        TransactionType transactionType,

        @Schema(description = "Human-readable location", example = "Cape Town, ZA")
        String location,

        @Schema(description = "Latitude", example = "-33.9249")
        Double latitude,

        @Schema(description = "Longitude", example = "18.4241")
        Double longitude
    ) {}
}
