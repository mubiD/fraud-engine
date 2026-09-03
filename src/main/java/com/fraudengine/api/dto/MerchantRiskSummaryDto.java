package com.fraudengine.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Pre-aggregated risk profile for a specific merchant")
public class MerchantRiskSummaryDto {

    @Schema(description = "Merchant identifier", example = "MERCH-NIKE-ZA")
    private String merchantId;

    @Schema(description = "Total transactions processed at this merchant (scoped to since if provided)", example = "1842")
    private long totalTransactions;

    @Schema(description = "Number of transactions flagged as fraudulent", example = "12")
    private long flaggedCount;

    @Schema(description = "Number of transactions not flagged as fraud (includes PENDING_REVIEW; "
            + "see GET /transactions/pending-review to distinguish confirmed-clean from awaiting-review)",
            example = "1830")
    private long notFlaggedCount;

    @Schema(description = "Fraud rate as a percentage (0–100)", example = "0.65")
    private double fraudRate;

    @Schema(description = "Highest risk score recorded for transactions at this merchant (0–100)", example = "85")
    private int highestRiskScore;

    @Schema(description = "Number of distinct customers who have transacted at this merchant (all-time)", example = "534")
    private long uniqueCustomers;

    @Schema(description = "Up to three fraud rules most frequently triggered at this merchant")
    private List<String> mostTriggeredRules;

    @Schema(description = "Timestamp of the earliest transaction on record at this merchant")
    private Instant firstTransactionAt;

    @Schema(description = "Timestamp of the most recent transaction on record at this merchant")
    private Instant lastTransactionAt;

    public MerchantRiskSummaryDto() {}

    public String getMerchantId() { return merchantId; }
    public long getTotalTransactions() { return totalTransactions; }
    public long getFlaggedCount() { return flaggedCount; }
    public long getNotFlaggedCount() { return notFlaggedCount; }
    public double getFraudRate() { return fraudRate; }
    public int getHighestRiskScore() { return highestRiskScore; }
    public long getUniqueCustomers() { return uniqueCustomers; }
    public List<String> getMostTriggeredRules() { return mostTriggeredRules; }
    public Instant getFirstTransactionAt() { return firstTransactionAt; }
    public Instant getLastTransactionAt() { return lastTransactionAt; }

    public void setMerchantId(String v) { this.merchantId = v; }
    public void setTotalTransactions(long v) { this.totalTransactions = v; }
    public void setFlaggedCount(long v) { this.flaggedCount = v; }
    public void setNotFlaggedCount(long v) { this.notFlaggedCount = v; }
    public void setFraudRate(double v) { this.fraudRate = v; }
    public void setHighestRiskScore(int v) { this.highestRiskScore = v; }
    public void setUniqueCustomers(long v) { this.uniqueCustomers = v; }
    public void setMostTriggeredRules(List<String> v) { this.mostTriggeredRules = v; }
    public void setFirstTransactionAt(Instant v) { this.firstTransactionAt = v; }
    public void setLastTransactionAt(Instant v) { this.lastTransactionAt = v; }
}
