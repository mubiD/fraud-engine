package com.fraudengine.kafka;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

public class FraudulentTransactionEvent {

    private UUID transactionId;
    private String customerId;
    private int riskScore;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant assessedAt;

    public FraudulentTransactionEvent() {}

    public UUID getTransactionId() { return transactionId; }
    public String getCustomerId() { return customerId; }
    public int getRiskScore() { return riskScore; }
    public Instant getAssessedAt() { return assessedAt; }

    public void setTransactionId(UUID v) { this.transactionId = v; }
    public void setCustomerId(String v) { this.customerId = v; }
    public void setRiskScore(int v) { this.riskScore = v; }
    public void setAssessedAt(Instant v) { this.assessedAt = v; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final FraudulentTransactionEvent e = new FraudulentTransactionEvent();
        public Builder transactionId(UUID v) { e.transactionId = v; return this; }
        public Builder customerId(String v) { e.customerId = v; return this; }
        public Builder riskScore(int v) { e.riskScore = v; return this; }
        public Builder assessedAt(Instant v) { e.assessedAt = v; return this; }
        public FraudulentTransactionEvent build() { return e; }
    }
}
