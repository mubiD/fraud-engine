package com.fraudengine.api.controller;

import com.fraudengine.api.dto.DataResponse;
import com.fraudengine.api.dto.FraudSummaryDto;
import com.fraudengine.service.TransactionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping(value = "/api/v1/stats", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@RateLimiter(name = "api")
@Tag(name = "Stats", description = "Aggregate fraud statistics for dashboards and reporting")
public class StatsController {

    private final TransactionQueryService queryService;

    public StatsController(TransactionQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/fraud-summary")
    @Operation(
        summary = "Get aggregate fraud statistics",
        description = """
            Returns total assessed, total flagged, fraud rate, and a per-rule breakdown for a given time window.
            If no date range is supplied the summary covers all data on record.
            Intended for fraud ops dashboards and compliance reporting.
            """
    )
    @ApiResponse(responseCode = "200", description = "Summary returned")
    @ApiResponse(responseCode = "400", description = "Invalid date-time parameters",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    public DataResponse<FraudSummaryDto> getFraudSummary(
            @Parameter(description = "ISO-8601 start of the window (inclusive)")
            @RequestParam(required = false) String from,
            @Parameter(description = "ISO-8601 end of the window (inclusive)")
            @RequestParam(required = false) String to) {

        Instant fromInstant = from != null ? Instant.parse(from) : null;
        Instant toInstant   = to   != null ? Instant.parse(to)   : null;

        return DataResponse.of(queryService.getFraudSummary(fromInstant, toInstant));
    }
}
