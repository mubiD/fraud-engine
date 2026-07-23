package com.fraudengine.api.controller;

import com.fraudengine.api.dto.FraudAssessmentDto;
import com.fraudengine.api.dto.PagedResponse;
import com.fraudengine.api.dto.TransactionSummaryDto;
import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.service.TransactionQueryService;
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
public class TransactionQueryController {

    private final TransactionQueryService queryService;
    private final TransactionMapper mapper;

    public TransactionQueryController(TransactionQueryService queryService, TransactionMapper mapper) {
        this.queryService = queryService;
        this.mapper = mapper;
    }

    @GetMapping
    public PagedResponse<TransactionSummaryDto> getByCustomerId(
            @RequestParam String customerId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(1000) int pageSize) {

        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;
        Slice<Transaction> slice = queryService.getByCustomerId(customerId, cursorInstant, pageSize);

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

    @GetMapping("/{transactionId}/assessment")
    public ResponseEntity<FraudAssessmentDto> getAssessment(@PathVariable UUID transactionId) {
        return queryService.getAssessment(transactionId)
                .map(mapper::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/flagged")
    public PagedResponse<FraudAssessmentDto> getFlagged(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String ruleViolated,
            @RequestParam(required = false) Integer minRiskScore,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(1000) int pageSize) {

        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;
        Slice<FraudAssessment> slice = queryService.getFlagged(
                customerId, ruleViolated, minRiskScore, cursorInstant, pageSize);

        return toAssessmentPage(slice);
    }

    @GetMapping("/passed")
    public PagedResponse<FraudAssessmentDto> getPassed(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(1000) int pageSize) {

        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;
        Slice<FraudAssessment> slice = queryService.getPassed(cursorInstant, pageSize);

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
