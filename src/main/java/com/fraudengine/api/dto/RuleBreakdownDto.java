package com.fraudengine.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How many times a given rule fired within a fraud summary window")
public class RuleBreakdownDto {

    @Schema(description = "Name of the rule", example = "AmountThresholdRule")
    private String ruleName;

    @Schema(description = "Number of flagged transactions that triggered this rule", example = "87")
    private long count;

    @Schema(description = "Percentage of flagged transactions in the window that triggered this rule", example = "27.88")
    private double percentage;

    public RuleBreakdownDto() {}

    public RuleBreakdownDto(String ruleName, long count, double percentage) {
        this.ruleName = ruleName;
        this.count = count;
        this.percentage = percentage;
    }

    public String getRuleName() { return ruleName; }
    public long getCount() { return count; }
    public double getPercentage() { return percentage; }

    public void setRuleName(String v) { this.ruleName = v; }
    public void setCount(long v) { this.count = v; }
    public void setPercentage(double v) { this.percentage = v; }
}
