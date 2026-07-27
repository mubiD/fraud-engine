package com.fraudengine.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Result of the fraud rule engine's assessment of a single transaction")
public class FraudAssessmentDto {

    @Schema(description = "Unique assessment identifier", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
    private UUID assessmentId;

    @Schema(description = "Transaction this assessment belongs to", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID transactionId;

    @Schema(description = "True if the rule engine determined the transaction to be fraudulent")
    private boolean fraudulent;

    @Schema(description = "Aggregate risk score (0–100); higher values indicate greater fraud likelihood", example = "72")
    private int riskScore;

    @Schema(description = "ISO-8601 UTC timestamp of when the assessment was completed", example = "2026-07-23T09:15:01Z")
    private Instant assessedAt;

    @Schema(description = "List of individual rule violations that contributed to the assessment")
    private List<RuleViolationDto> violations;

    public FraudAssessmentDto() {}

    public UUID getAssessmentId() { return assessmentId; }
    public UUID getTransactionId() { return transactionId; }
    public boolean isFraudulent() { return fraudulent; }
    public int getRiskScore() { return riskScore; }
    public Instant getAssessedAt() { return assessedAt; }
    public List<RuleViolationDto> getViolations() { return violations; }

    public void setAssessmentId(UUID v) { this.assessmentId = v; }
    public void setTransactionId(UUID v) { this.transactionId = v; }
    public void setFraudulent(boolean v) { this.fraudulent = v; }
    public void setRiskScore(int v) { this.riskScore = v; }
    public void setAssessedAt(Instant v) { this.assessedAt = v; }
    public void setViolations(List<RuleViolationDto> v) { this.violations = v; }
}
