package com.fraudengine.api.dto;

public class RuleViolationDto {

    private String ruleName;
    private String ruleVersion;
    private String description;
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
