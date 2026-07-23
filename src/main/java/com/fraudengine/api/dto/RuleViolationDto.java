package com.fraudengine.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A single rule that fired and contributed to a fraud assessment")
public class RuleViolationDto {

    @Schema(description = "Name of the rule that fired", example = "AmountThresholdRule")
    private String ruleName;

    @Schema(description = "Version of the rule at the time of evaluation", example = "1.0")
    private String ruleVersion;

    @Schema(description = "Human-readable explanation of why the rule fired",
            example = "Transaction amount 6500.00 ZAR exceeds the configured threshold of 5000.00 ZAR")
    private String description;

    @Schema(description = "Severity level of this violation", allowableValues = {"LOW", "MEDIUM", "HIGH", "CRITICAL"}, example = "HIGH")
    private String severity;

    public RuleViolationDto() {}

    public String getRuleName() { return ruleName; }
    public String getRuleVersion() { return ruleVersion; }
    public String getDescription() { return description; }
    public String getSeverity() { return severity; }

    public void setRuleName(String v) { this.ruleName = v; }
    public void setRuleVersion(String v) { this.ruleVersion = v; }
    public void setDescription(String v) { this.description = v; }
    public void setSeverity(String v) { this.severity = v; }
}
