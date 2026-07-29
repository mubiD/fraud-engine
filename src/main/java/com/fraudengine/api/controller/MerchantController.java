package com.fraudengine.api.controller;

import com.fraudengine.api.dto.FraudAssessmentDto;
import com.fraudengine.api.dto.MerchantRiskSummaryDto;
import com.fraudengine.api.dto.PagedResponse;
import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.model.FraudAssessment;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/merchants")
@Validated
@Tag(name = "Merchants", description = "Merchant-scoped fraud views")
public class MerchantController {

    private final TransactionQueryService queryService;
    private final TransactionMapper mapper;

    public MerchantController(TransactionQueryService queryService, TransactionMapper mapper) {
        this.queryService = queryService;
        this.mapper = mapper;
    }

    @GetMapping("/{merchantId}/flagged")
    @Operation(
        summary = "List fraudulent transactions for a merchant",
        description = "Returns cursor-paginated fraud assessments for transactions at the given merchant. Useful for investigating a specific merchant following a tip or pattern detection."
    )
    @ApiResponse(responseCode = "200", description = "Flagged assessments returned")
    @ApiResponse(responseCode = "400", description = "Invalid request parameters",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    public PagedResponse<FraudAssessmentDto> getFlaggedByMerchant(
            @Parameter(description = "Merchant identifier", required = true)
            @PathVariable String merchantId,
            @Parameter(description = "Filter by the name of the rule that was violated (e.g. VelocityRule)")
            @RequestParam(required = false) String ruleViolated,
            @Parameter(description = "Filter to assessments with a risk score at or above this value (inclusive)")
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

        Slice<FraudAssessment> slice = queryService.getFlaggedByMerchant(
                merchantId, ruleViolated, minRiskScore, fromInstant, toInstant, cursorInstant, pageSize);

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

    @GetMapping("/{merchantId}/risk-summary")
    @Operation(
        summary = "Get risk summary for a merchant",
        description = """
            Returns a pre-aggregated risk profile for the given merchant: total transactions, fraud rate,
            highest risk score recorded, most-triggered rules, unique customer count, and activity timestamps.
            Use `since` to scope activity metrics to a recent window (e.g. last 30 days).
            uniqueCustomers, firstTransactionAt, and lastTransactionAt are always all-time values.
            Useful for merchant risk teams and onboarding reviews.
            """
    )
    @ApiResponse(responseCode = "200", description = "Merchant risk summary returned")
    public MerchantRiskSummaryDto getMerchantRiskSummary(
            @Parameter(description = "Merchant identifier", required = true)
            @PathVariable String merchantId,
            @Parameter(description = "ISO-8601 timestamp; scopes activity metrics to this point in time onwards")
            @RequestParam(required = false) String since) {
        Instant sinceInstant = since != null ? Instant.parse(since) : null;
        return queryService.getMerchantRiskSummary(merchantId, sinceInstant);
    }
}
