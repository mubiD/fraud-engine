package com.fraudengine.api.controller;

import com.fraudengine.api.dto.FraudAssessmentDto;
import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.enums.AssessmentOutcome;
import com.fraudengine.service.AssessmentOutcomeService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@RateLimiter(name = "api")
@Tag(name = "Transaction Outcomes", description = "Record the real-world ground-truth outcome of a fraud assessment")
public class TransactionOutcomeController {

    private final AssessmentOutcomeService outcomeService;
    private final TransactionMapper mapper;

    public TransactionOutcomeController(AssessmentOutcomeService outcomeService, TransactionMapper mapper) {
        this.outcomeService = outcomeService;
        this.mapper = mapper;
    }

    @PatchMapping(value = "/{transactionId}/outcome", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Record the ground-truth outcome of a transaction's fraud assessment",
        description = """
            The one write endpoint in an otherwise read-only API. Lets a fraud analyst record whether
            a transaction was confirmed fraud or a false positive after review. Outcomes are a one-time
            disposition — once set away from UNRESOLVED they cannot be changed again.
            """
    )
    @ApiResponse(responseCode = "200", description = "Outcome recorded")
    @ApiResponse(responseCode = "400", description = "Invalid outcome value")
    @ApiResponse(responseCode = "404", description = "No assessment exists for this transaction")
    @ApiResponse(responseCode = "409", description = "Outcome already resolved")
    public ResponseEntity<FraudAssessmentDto> updateOutcome(
            @PathVariable UUID transactionId,
            @RequestBody @Valid UpdateOutcomeRequest request) {
        FraudAssessment updated = outcomeService.updateOutcome(transactionId, request.outcome());
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    @Schema(description = "Ground-truth outcome to record for a transaction's fraud assessment")
    record UpdateOutcomeRequest(
        @Schema(description = "Ground-truth outcome", allowableValues = {"CONFIRMED_FRAUD", "FALSE_POSITIVE"}, example = "CONFIRMED_FRAUD")
        @NotNull AssessmentOutcome outcome
    ) {}
}
