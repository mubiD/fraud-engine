package com.fraudengine.api.dto;

public class RuleDto {

    private String ruleName;
    private String ruleVersion;
    private int priority;
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
