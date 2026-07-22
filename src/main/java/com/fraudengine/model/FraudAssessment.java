package com.fraudengine.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "fraud_assessments")
public class FraudAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "transaction_id", referencedColumnName = "id", nullable = false),
        @JoinColumn(name = "transaction_timestamp", referencedColumnName = "timestamp", nullable = false)
    })
    private Transaction transaction;

    @Column(name = "transaction_timestamp", nullable = false, insertable = false, updatable = false)
    private Instant transactionTimestamp;

    @Column(name = "is_fraudulent", nullable = false)
    private boolean fraudulent;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt = Instant.now();

    @OneToMany(mappedBy = "assessment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RuleViolation> ruleViolations;

    public FraudAssessment() {}

    public UUID getId() { return id; }
    public Transaction getTransaction() { return transaction; }
    public Instant getTransactionTimestamp() { return transactionTimestamp; }
    public boolean isFraudulent() { return fraudulent; }
    public int getRiskScore() { return riskScore; }
    public Instant getAssessedAt() { return assessedAt; }
    public List<RuleViolation> getRuleViolations() { return ruleViolations; }

    public void setId(UUID id) { this.id = id; }
    public void setTransaction(Transaction transaction) { this.transaction = transaction; }
    public void setFraudulent(boolean fraudulent) { this.fraudulent = fraudulent; }
    public void setRiskScore(int riskScore) { this.riskScore = riskScore; }
    public void setAssessedAt(Instant assessedAt) { this.assessedAt = assessedAt; }
    public void setRuleViolations(List<RuleViolation> ruleViolations) { this.ruleViolations = ruleViolations; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final FraudAssessment a = new FraudAssessment();
        public Builder transaction(Transaction v) { a.transaction = v; return this; }
        public Builder fraudulent(boolean v) { a.fraudulent = v; return this; }
        public Builder riskScore(int v) { a.riskScore = v; return this; }
        public FraudAssessment build() { return a; }
    }
}
