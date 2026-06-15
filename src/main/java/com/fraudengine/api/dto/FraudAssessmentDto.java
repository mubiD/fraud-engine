package com.fraudengine.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class FraudAssessmentDto {

    private UUID assessmentId;
    private UUID transactionId;
    private boolean fraudulent;
    private int riskScore;
    private Instant assessedAt;
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
