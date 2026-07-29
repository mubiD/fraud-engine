package com.fraudengine.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Aggregate fraud statistics for a given time window")
public class FraudSummaryDto {

    @Schema(description = "Start of the window (inclusive); null if no lower bound was applied")
    private Instant from;

    @Schema(description = "End of the window (inclusive); null if no upper bound was applied")
    private Instant to;

    @Schema(description = "Total transactions assessed in the window", example = "10000")
    private long totalAssessed;

    @Schema(description = "Number of transactions flagged as fraudulent", example = "312")
    private long totalFlagged;

    @Schema(description = "Number of transactions that passed fraud checks", example = "9688")
    private long totalPassed;

    @Schema(description = "Fraud rate as a percentage (0–100), rounded to two decimal places", example = "3.12")
    private double fraudRate;

    @Schema(description = "Breakdown of which rules fired and how often, ordered by count descending")
    private List<RuleBreakdownDto> ruleBreakdown;

    public FraudSummaryDto() {}

    public Instant getFrom() { return from; }
    public Instant getTo() { return to; }
    public long getTotalAssessed() { return totalAssessed; }
    public long getTotalFlagged() { return totalFlagged; }
    public long getTotalPassed() { return totalPassed; }
    public double getFraudRate() { return fraudRate; }
    public List<RuleBreakdownDto> getRuleBreakdown() { return ruleBreakdown; }

    public void setFrom(Instant v) { this.from = v; }
    public void setTo(Instant v) { this.to = v; }
    public void setTotalAssessed(long v) { this.totalAssessed = v; }
    public void setTotalFlagged(long v) { this.totalFlagged = v; }
    public void setTotalPassed(long v) { this.totalPassed = v; }
    public void setFraudRate(double v) { this.fraudRate = v; }
    public void setRuleBreakdown(List<RuleBreakdownDto> v) { this.ruleBreakdown = v; }
}
