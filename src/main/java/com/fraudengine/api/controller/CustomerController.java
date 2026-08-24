package com.fraudengine.api.controller;

import com.fraudengine.api.dto.DataResponse;
import com.fraudengine.api.dto.CustomerRiskSummaryDto;
import com.fraudengine.service.TransactionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping(value = "/api/v1/customers", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@RateLimiter(name = "api")
@Tag(name = "Customers", description = "Customer-level risk views")
public class CustomerController {

    private final TransactionQueryService queryService;

    public CustomerController(TransactionQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{customerId}/risk-summary")
    @Operation(
        summary = "Get risk summary for a customer",
        description = """
            Returns a pre-aggregated risk profile for the given customer: total transactions, fraud rate,
            highest risk score ever seen, most-triggered rules, and activity timestamps.
            Use `since` to scope activity metrics to a recent window (e.g. last 30 days).
            firstTransactionAt and lastTransactionAt are always all-time values.
            Designed for customer service agents who need a quick risk read before taking an action
            (e.g. approving a dispute or escalating a case).
            """
    )
    @ApiResponse(responseCode = "200", description = "Risk summary returned")
    public DataResponse<CustomerRiskSummaryDto> getCustomerRiskSummary(
            @Parameter(description = "Customer identifier", required = true)
            @PathVariable String customerId,
            @Parameter(description = "ISO-8601 timestamp; scopes activity metrics to this point in time onwards")
            @RequestParam(required = false) String since) {
        Instant sinceInstant = since != null ? Instant.parse(since) : null;
        return DataResponse.of(queryService.getCustomerRiskSummary(customerId, sinceInstant));
    }
}
