package com.fraudengine.engine;

import com.fraudengine.model.enums.Severity;

public class RuleResult {

    private final boolean violation;
    private final String ruleName;
    private final String ruleVersion;
    private final String description;
    private final Severity severity;

    private RuleResult(boolean violation, String ruleName, String ruleVersion,
                       String description, Severity severity) {
        this.violation = violation;
        this.ruleName = ruleName;
        this.ruleVersion = ruleVersion;
        this.description = description;
        this.severity = severity;
    }

    public static RuleResult pass(String ruleName) {
        return new RuleResult(false, ruleName, null, null, null);
    }

    public static RuleResult violation(String ruleName, String ruleVersion,
                                       String description, Severity severity) {
        return new RuleResult(true, ruleName, ruleVersion, description, severity);
    }

    public boolean isViolation() { return violation; }
    public String getRuleName() { return ruleName; }
    public String getRuleVersion() { return ruleVersion; }
    public String getDescription() { return description; }
    public Severity getSeverity() { return severity; }
}
