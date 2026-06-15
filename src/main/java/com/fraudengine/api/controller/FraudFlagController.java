package com.fraudengine.api.controller;

import com.fraudengine.api.dto.FraudAssessmentDto;
import com.fraudengine.api.dto.PagedResponse;
import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.service.FraudFlagService;
import org.springframework.data.domain.Slice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class FraudFlagController {

    private final FraudFlagService fraudFlagService;
    private final TransactionMapper mapper;

    public FraudFlagController(FraudFlagService fraudFlagService, TransactionMapper mapper) {
        this.fraudFlagService = fraudFlagService;
        this.mapper = mapper;
    }

    @GetMapping("/transactions/{transactionId}/assessment")
    public ResponseEntity<FraudAssessmentDto> getAssessment(@PathVariable UUID transactionId) {
        return fraudFlagService.getAssessment(transactionId)
                .map(mapper::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/fraud-flags")
    public PagedResponse<FraudAssessmentDto> getFraudFlags(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String ruleViolated,
            @RequestParam(required = false) Integer minRiskScore,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int pageSize) {

        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;
        Slice<FraudAssessment> slice = fraudFlagService.getFraudFlags(
                customerId, ruleViolated, minRiskScore, cursorInstant, pageSize);

        List<FraudAssessmentDto> data = slice.getContent().stream()
                .map(mapper::toDto)
                .toList();

        Instant nextCursor = slice.hasNext() && !data.isEmpty()
                ? data.get(data.size() - 1).getAssessedAt()
                : null;

        PagedResponse<FraudAssessmentDto> response = new PagedResponse<>();
        response.setData(data);
        response.setNextCursor(nextCursor);
        response.setHasMore(slice.hasNext());
        return response;
    }
}
