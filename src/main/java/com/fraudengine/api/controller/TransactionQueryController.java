package com.fraudengine.api.controller;

import com.fraudengine.api.dto.FraudAssessmentDto;
import com.fraudengine.api.dto.PagedResponse;
import com.fraudengine.api.dto.TransactionSummaryDto;
import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.service.TransactionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Slice;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@Validated
@Tag(name = "Transactions", description = "Query transactions and their fraud assessments")
public class TransactionQueryController {

    private final TransactionQueryService queryService;
    private final TransactionMapper mapper;

    public TransactionQueryController(TransactionQueryService queryService, TransactionMapper mapper) {
        this.queryService = queryService;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(
        summary = "List transactions for a customer",
        description = "Returns a cursor-paginated list of transactions for the given customer, ordered by timestamp descending. Optionally scoped to a date range."
    )
    @ApiResponse(responseCode = "200", description = "Transactions returned")
    @ApiResponse(responseCode = "400", description = "Invalid request parameters",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    public PagedResponse<TransactionSummaryDto> getByCustomerId(
            @Parameter(description = "Customer identifier", required = true)
            @RequestParam String customerId,
            @Parameter(description = "ISO-8601 start of date range (inclusive)")
            @RequestParam(required = false) String from,
            @Parameter(description = "ISO-8601 end of date range (inclusive)")
            @RequestParam(required = false) String to,
            @Parameter(description = "ISO-8601 timestamp cursor from the previous page's nextCursor field")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Number of results per page (1–1000)", schema = @Schema(defaultValue = "20"))
            @RequestParam(defaultValue = "20") @Min(1) @Max(1000) int pageSize) {

        Instant fromInstant   = from   != null ? Instant.parse(from)   : null;
        Instant toInstant     = to     != null ? Instant.parse(to)     : null;
        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;

        Slice<Transaction> slice = queryService.getByCustomerId(
                customerId, fromInstant, toInstant, cursorInstant, pageSize);

        List<TransactionSummaryDto> data = slice.getContent().stream()
                .map(mapper::toSummaryDto)
                .toList();

        Instant nextCursor = slice.hasNext() && !data.isEmpty()
                ? data.get(data.size() - 1).getTimestamp()
                : null;

        return PagedResponse.<TransactionSummaryDto>builder()
                .data(data)
                .nextCursor(nextCursor)
                .hasMore(slice.hasNext())
                .build();
    }

    @GetMapping("/{transactionId}")
    @Operation(
        summary = "Get a single transaction by ID",
        description = "Returns the transaction details including its fraud assessment result if one exists."
    )
    @ApiResponse(responseCode = "200", description = "Transaction found")
    @ApiResponse(responseCode = "404", description = "No transaction exists for the given ID")
    public ResponseEntity<TransactionSummaryDto> getById(
            @Parameter(description = "UUID of the transaction", required = true)
            @PathVariable UUID transactionId) {
        return queryService.getById(transactionId)
                .map(mapper::toSummaryDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{transactionId}/assessment")
    @Operation(
        summary = "Get fraud assessment for a transaction",
        description = "Returns the fraud assessment result for the specified transaction, including risk score and any rule violations."
    )
    @ApiResponse(responseCode = "200", description = "Assessment found")
    @ApiResponse(responseCode = "404", description = "No assessment exists for the given transaction ID")
    public ResponseEntity<FraudAssessmentDto> getAssessment(
            @Parameter(description = "UUID of the transaction", required = true)
            @PathVariable UUID transactionId) {
        return queryService.getAssessment(transactionId)
                .map(mapper::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/flagged")
    @Operation(
        summary = "List flagged (fraudulent) transactions",
        description = "Returns cursor-paginated fraud assessments where the transaction was flagged as fraudulent. All filter parameters are optional and combinable."
    )
    @ApiResponse(responseCode = "200", description = "Flagged assessments returned")
    @ApiResponse(responseCode = "400", description = "Invalid request parameters",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    public PagedResponse<FraudAssessmentDto> getFlagged(
            @Parameter(description = "Filter by customer identifier")
            @RequestParam(required = false) String customerId,
            @Parameter(description = "Filter by the name of the rule that was violated (e.g. AmountThresholdRule)")
            @RequestParam(required = false) String ruleViolated,
            @Parameter(description = "Filter to assessments with a risk score at or above this value (inclusive)")
            @RequestParam(required = false) Integer minRiskScore,
            @Parameter(description = "Filter to assessments with a risk score at or below this value (inclusive). Combine with minRiskScore to query a band, e.g. 50–65 for low-confidence fraud review.")
            @RequestParam(required = false) Integer maxRiskScore,
            @Parameter(description = "ISO-8601 start of date range (inclusive)")
            @RequestParam(required = false) String from,
            @Parameter(description = "ISO-8601 end of date range (inclusive)")
            @RequestParam(required = false) String to,
            @Parameter(description = "ISO-8601 timestamp cursor from the previous page's nextCursor field")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Number of results per page (1–1000)", schema = @Schema(defaultValue = "20"))
            @RequestParam(defaultValue = "20") @Min(1) @Max(1000) int pageSize) {

        Instant fromInstant   = from   != null ? Instant.parse(from)   : null;
        Instant toInstant     = to     != null ? Instant.parse(to)     : null;
        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;

        Slice<FraudAssessment> slice = queryService.getFlagged(
                customerId, ruleViolated, minRiskScore, maxRiskScore, fromInstant, toInstant, cursorInstant, pageSize);

        return toAssessmentPage(slice);
    }

    @GetMapping("/passed")
    @Operation(
        summary = "List passed (cleared) transactions",
        description = "Returns cursor-paginated fraud assessments where the transaction was cleared by the rule engine. Optionally filtered by customer and date range."
    )
    @ApiResponse(responseCode = "200", description = "Passed assessments returned")
    @ApiResponse(responseCode = "400", description = "Invalid request parameters",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    public PagedResponse<FraudAssessmentDto> getPassed(
            @Parameter(description = "Filter by customer identifier")
            @RequestParam(required = false) String customerId,
            @Parameter(description = "Filter to assessments with a risk score at or above this value. Use to surface near-misses: transactions that almost triggered a fraud flag.")
            @RequestParam(required = false) Integer minRiskScore,
            @Parameter(description = "ISO-8601 start of date range (inclusive)")
            @RequestParam(required = false) String from,
            @Parameter(description = "ISO-8601 end of date range (inclusive)")
            @RequestParam(required = false) String to,
            @Parameter(description = "ISO-8601 timestamp cursor from the previous page's nextCursor field")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Number of results per page (1–1000)", schema = @Schema(defaultValue = "20"))
            @RequestParam(defaultValue = "20") @Min(1) @Max(1000) int pageSize) {

        Instant fromInstant   = from   != null ? Instant.parse(from)   : null;
        Instant toInstant     = to     != null ? Instant.parse(to)     : null;
        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;

        Slice<FraudAssessment> slice = queryService.getPassed(
                customerId, minRiskScore, fromInstant, toInstant, cursorInstant, pageSize);

        return toAssessmentPage(slice);
    }

    private PagedResponse<FraudAssessmentDto> toAssessmentPage(Slice<FraudAssessment> slice) {
        List<FraudAssessmentDto> data = slice.getContent().stream()
                .map(mapper::toDto)
                .toList();

        Instant nextCursor = slice.hasNext() && !data.isEmpty()
                ? data.get(data.size() - 1).getAssessedAt()
                : null;

        return PagedResponse.<FraudAssessmentDto>builder()
                .data(data)
                .nextCursor(nextCursor)
                .hasMore(slice.hasNext())
                .build();
    }
}
