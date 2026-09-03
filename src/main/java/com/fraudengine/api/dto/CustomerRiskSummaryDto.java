package com.fraudengine.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Pre-aggregated risk profile for a specific customer")
public class CustomerRiskSummaryDto {

    @Schema(description = "Customer identifier", example = "CUST-001")
    private String customerId;

    @Schema(description = "Total transactions on record for this customer", example = "342")
    private long totalTransactions;

    @Schema(description = "Number of transactions flagged as fraudulent", example = "4")
    private long flaggedCount;

    @Schema(description = "Number of transactions not flagged as fraud (includes PENDING_REVIEW; "
            + "see GET /transactions/pending-review to distinguish confirmed-clean from awaiting-review)",
            example = "338")
    private long notFlaggedCount;

    @Schema(description = "Fraud rate as a percentage (0–100)", example = "1.17")
    private double fraudRate;

    @Schema(description = "Highest risk score ever recorded for this customer (0–100)", example = "75")
    private int highestRiskScore;

    @Schema(description = "Up to three fraud rules most frequently triggered by this customer's transactions")
    private List<String> mostTriggeredRules;

    @Schema(description = "Timestamp of the customer's earliest transaction on record")
    private Instant firstTransactionAt;

    @Schema(description = "Timestamp of the customer's most recent transaction on record")
    private Instant lastTransactionAt;

    public CustomerRiskSummaryDto() {}

    public String getCustomerId() { return customerId; }
    public long getTotalTransactions() { return totalTransactions; }
    public long getFlaggedCount() { return flaggedCount; }
    public long getNotFlaggedCount() { return notFlaggedCount; }
    public double getFraudRate() { return fraudRate; }
    public int getHighestRiskScore() { return highestRiskScore; }
    public List<String> getMostTriggeredRules() { return mostTriggeredRules; }
    public Instant getFirstTransactionAt() { return firstTransactionAt; }
    public Instant getLastTransactionAt() { return lastTransactionAt; }

    public void setCustomerId(String v) { this.customerId = v; }
    public void setTotalTransactions(long v) { this.totalTransactions = v; }
    public void setFlaggedCount(long v) { this.flaggedCount = v; }
    public void setNotFlaggedCount(long v) { this.notFlaggedCount = v; }
    public void setFraudRate(double v) { this.fraudRate = v; }
    public void setHighestRiskScore(int v) { this.highestRiskScore = v; }
    public void setMostTriggeredRules(List<String> v) { this.mostTriggeredRules = v; }
    public void setFirstTransactionAt(Instant v) { this.firstTransactionAt = v; }
    public void setLastTransactionAt(Instant v) { this.lastTransactionAt = v; }
}
