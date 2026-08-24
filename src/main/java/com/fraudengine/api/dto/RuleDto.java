package com.fraudengine.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Fraud rule registered with the rule engine")
public class RuleDto {

    @Schema(description = "Canonical rule name", example = "AmountThresholdRule")
    private String ruleName;

    @Schema(description = "Rule version string", example = "1.0")
    private String ruleVersion;

    @Schema(description = "Evaluation priority; lower values run first", example = "10")
    private int priority;

    @Schema(description = "Whether this rule is currently active in the engine")
    private boolean enabled;

    public RuleDto() {}

    public String getRuleName() { return ruleName; }
    public String getRuleVersion() { return ruleVersion; }
    public int getPriority() { return priority; }
    public boolean isEnabled() { return enabled; }

    public void setRuleName(String v) { this.ruleName = v; }
    public void setRuleVersion(String v) { this.ruleVersion = v; }
    public void setPriority(int v) { this.priority = v; }
    public void setEnabled(boolean v) { this.enabled = v; }
}
