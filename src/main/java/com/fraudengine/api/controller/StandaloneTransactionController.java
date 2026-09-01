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
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
@RequestMapping(value = "/api/v1/standalone", produces = MediaType.APPLICATION_JSON_VALUE)
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

    @PostMapping(value = "/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimiter(name = "standalone-submit")
    @Transactional
    @Operation(
        summary = "Submit a transaction for fraud assessment (standalone demo)",
        description = """
            STUB: Persists the transaction, runs it through the fraud rule engine, and returns the assessment.
            In production this path is replaced by the Kafka consumer pipeline
            (TransactionConsumer → RuleEngine → AssessmentProducer).

            Idempotency: supply a `transactionId` UUID in the request body. If a transaction with that ID
            has already been processed, the existing assessment is returned immediately without re-evaluation.
            Omit `transactionId` to let the server assign one (no idempotency guarantee).
            """
    )
    @ApiResponse(responseCode = "200", description = "Assessment completed")
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    public ResponseEntity<FraudAssessmentDto> submit(@RequestBody @Valid TransactionRequest request) {
        if (request.transactionId() != null) {
            Optional<Transaction> existing = transactionRepository.findByIdOnly(request.transactionId());
            if (existing.isPresent()) {
                FraudAssessment existingAssessment = fraudAssessmentRepository
                        .findByTransactionId(request.transactionId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Transaction " + request.transactionId() + " exists but has no assessment"));
                return ResponseEntity.ok(mapper.toDto(existingAssessment));
            }
        }

        Transaction tx = Transaction.builder()
                .id(request.transactionId())
                .customerId(request.customerId())
                .merchantId(request.merchantId())
                .amount(request.amount())
                .currency(request.currency())
                .category(request.category())
                .location(request.location())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .deviceFingerprint(request.deviceFingerprint())
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

    @PostMapping("/stream")
    @Operation(
        summary = "Stream N fake transactions through the fraud engine (standalone demo)",
        description = """
            STUB: Generates and processes N randomised transactions through the rule engine.
            Roughly 15% will exceed the amount threshold and ~10% will hit the merchant blacklist.
            Use this to quickly populate the DB and observe fraud rule behaviour at scale.
            """
    )
    @ApiResponse(responseCode = "200", description = "Streaming complete")
    @ApiResponse(responseCode = "400", description = "count out of range")
    public ResponseEntity<StreamResult> stream(
            @RequestParam @Min(1) @Max(10_000) int count) {

        int passed = 0;
        int pendingReview = 0;
        int flagged = 0;

        for (int i = 0; i < count; i++) {
            Transaction tx = buildFakeTransaction();
            tx = transactionRepository.save(tx);

            FraudAssessment assessment = ruleEngine.evaluate(tx);
            fraudAssessmentRepository.save(assessment);

            tx.setStatus(TransactionStatus.ASSESSED);
            transactionRepository.save(tx);

            switch (assessment.getDisposition()) {
                case FLAGGED -> flagged++;
                case PENDING_REVIEW -> pendingReview++;
                case CLEARED -> passed++;
            }
        }

        return ResponseEntity.ok(new StreamResult(count, passed, pendingReview, flagged));
    }

    private Transaction buildFakeTransaction() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        String[] customers = {"CUST-001","CUST-002","CUST-003","CUST-004","CUST-005",
                              "CUST-006","CUST-007","CUST-008","CUST-009","CUST-010"};
        String[] merchants  = {"MERCH-WOOLWORTHS-ZA","MERCH-CHECKERS-ZA","MERCH-PICK-N-PAY-ZA",
                               "MERCH-SHOPRITE-ZA","MERCH-CLICKS-ZA","MERCH-DISCHEM-ZA"};
        String[] fraudMerch = {"MERCHANT_FRAUD_001","MERCHANT_FRAUD_002","MERCHANT_FRAUD_003"};
        String[][] locations = {
            {"-33.9249","18.4241","Cape Town, ZA"},
            {"-26.2041","28.0473","Johannesburg, ZA"},
            {"-29.8587","31.0218","Durban, ZA"},
            {"-25.7479","28.2293","Pretoria, ZA"},
            {"-26.1070","28.0567","Sandton, ZA"}
        };
        String[] categories = {"RETAIL","GROCERY","PHARMACY","FUEL","DINING"};
        TransactionType[] types = TransactionType.values();

        boolean isFraudMerchant = rng.nextInt(100) < 10;
        boolean isHighAmount    = rng.nextInt(100) < 15;

        String merchantId = isFraudMerchant
                ? fraudMerch[rng.nextInt(fraudMerch.length)]
                : merchants[rng.nextInt(merchants.length)];

        BigDecimal amount = isHighAmount
                ? BigDecimal.valueOf(rng.nextDouble(5001, 50_000)).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(rng.nextDouble(10, 4999)).setScale(2, RoundingMode.HALF_UP);

        String[] loc = locations[rng.nextInt(locations.length)];

        return Transaction.builder()
                .customerId(customers[rng.nextInt(customers.length)])
                .merchantId(merchantId)
                .amount(amount)
                .currency("ZAR")
                .category(categories[rng.nextInt(categories.length)])
                .transactionType(types[rng.nextInt(types.length)])
                .location(loc[2])
                .latitude(Double.parseDouble(loc[0]))
                .longitude(Double.parseDouble(loc[1]))
                .timestamp(Instant.now())
                .status(TransactionStatus.PENDING)
                .build();
    }

    @Schema(description = "Transaction to submit for fraud assessment")
    record TransactionRequest(

        @Schema(description = "Idempotency key — if provided and already processed, the existing assessment is returned without reprocessing",
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID transactionId,

        @Schema(description = "Customer identifier", example = "CUST-001")
        @NotBlank String customerId,

        @Schema(description = "Merchant identifier — use MERCHANT_FRAUD_001/002/003 to trigger the blacklist rule",
                example = "MERCH-NIKE-ZA")
        @NotBlank String merchantId,

        @Schema(description = "Transaction amount — values above 5000 trigger the AmountThresholdRule",
                example = "6500.00")
        @NotNull @Positive BigDecimal amount,

        @Schema(description = "ISO 4217 currency code", example = "ZAR")
        @NotBlank @Size(min = 3, max = 3) @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase ISO 4217 currency code") String currency,

        @Schema(description = "Merchant category", example = "RETAIL")
        String category,

        @Schema(description = "Transaction channel — CARD_PRESENT, CARD_NOT_PRESENT, CONTACTLESS, ATM")
        TransactionType transactionType,

        @Schema(description = "Human-readable location", example = "Cape Town, ZA")
        String location,

        @Schema(description = "Latitude", example = "-33.9249")
        @DecimalMin("-90.0") @DecimalMax("90.0")
        Double latitude,

        @Schema(description = "Longitude", example = "18.4241")
        @DecimalMin("-180.0") @DecimalMax("180.0")
        Double longitude,

        @Schema(description = "Device fingerprint (e.g. hashed user-agent + IP). "
                + "When present, triggers DeviceFingerprintRule if the device is new for this customer.",
                example = "a3f1c2e9b7d04562")
        String deviceFingerprint
    ) {}

    @Schema(description = "Summary of a stream run")
    record StreamResult(
        @Schema(description = "Total transactions processed") int total,
        @Schema(description = "Transactions cleared") int passed,
        @Schema(description = "Transactions marked pending review") int pendingReview,
        @Schema(description = "Transactions flagged as fraudulent") int flagged
    ) {}
}
