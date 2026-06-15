package com.fraudengine.model;

import com.fraudengine.model.enums.Severity;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "rule_violations")
public class RuleViolation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false)
    private FraudAssessment assessment;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "rule_version", nullable = false)
    private String ruleVersion;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    public RuleViolation() {}

    public UUID getId() { return id; }
    public FraudAssessment getAssessment() { return assessment; }
    public String getRuleName() { return ruleName; }
    public String getRuleVersion() { return ruleVersion; }
    public String getDescription() { return description; }
    public Severity getSeverity() { return severity; }

    public void setId(UUID id) { this.id = id; }
    public void setAssessment(FraudAssessment assessment) { this.assessment = assessment; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
    public void setDescription(String description) { this.description = description; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final RuleViolation v = new RuleViolation();
        public Builder assessment(FraudAssessment a) { v.assessment = a; return this; }
        public Builder ruleName(String s) { v.ruleName = s; return this; }
        public Builder ruleVersion(String s) { v.ruleVersion = s; return this; }
        public Builder description(String s) { v.description = s; return this; }
        public Builder severity(Severity s) { v.severity = s; return this; }
        public RuleViolation build() { return v; }
    }
}
