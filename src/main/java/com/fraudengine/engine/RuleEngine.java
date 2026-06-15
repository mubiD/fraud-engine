package com.fraudengine.engine;

import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.RuleViolation;
import com.fraudengine.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final List<FraudRule> rules;
    private final EvaluationContextBuilder contextBuilder;

    public RuleEngine(List<FraudRule> rules, EvaluationContextBuilder contextBuilder) {
        this.rules = rules;
        this.contextBuilder = contextBuilder;
    }

    public FraudAssessment evaluate(Transaction transaction) {
        log.debug("Evaluating {} rules for transaction {}", rules.size(), transaction.getId());

        EvaluationContext context = contextBuilder.build(transaction);

        List<RuleResult> violations = rules.stream()
                .filter(FraudRule::isEnabled)
                .sorted(Comparator.comparingInt(FraudRule::getPriority))
                .map(rule -> rule.evaluate(transaction, context))
                .filter(RuleResult::isViolation)
                .collect(Collectors.toList());

        int riskScore = calculateRiskScore(violations);
        boolean isFraudulent = riskScore >= 50;

        FraudAssessment assessment = FraudAssessment.builder()
                .transaction(transaction)
                .fraudulent(isFraudulent)
                .riskScore(riskScore)
                .build();

        List<RuleViolation> ruleViolations = violations.stream()
                .map(result -> RuleViolation.builder()
                        .assessment(assessment)
                        .ruleName(result.getRuleName())
                        .ruleVersion(result.getRuleVersion())
                        .description(result.getDescription())
                        .severity(result.getSeverity())
                        .build())
                .collect(Collectors.toList());

        assessment.setRuleViolations(ruleViolations);

        log.info("Transaction {} assessed: fraudulent={}, riskScore={}, violations={}",
                transaction.getId(), isFraudulent, riskScore, violations.size());

        return assessment;
    }

    private int calculateRiskScore(List<RuleResult> violations) {
        int raw = violations.stream()
                .mapToInt(v -> v.getSeverity().getScoreWeight())
                .sum();
        return Math.min(raw, 100);
    }

    public List<FraudRule> getRules() {
        return rules.stream()
                .sorted(Comparator.comparingInt(FraudRule::getPriority))
                .collect(Collectors.toList());
    }
}
