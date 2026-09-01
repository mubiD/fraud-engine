package com.fraudengine.engine;

import com.fraudengine.config.ScoringProperties;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.enums.Disposition;
import com.fraudengine.model.RuleViolation;
import com.fraudengine.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final List<FraudRule> rules;
    private final EvaluationContextBuilder contextBuilder;
    private final ScoringProperties scoringProperties;

    public RuleEngine(List<FraudRule> rules, EvaluationContextBuilder contextBuilder,
                       ScoringProperties scoringProperties) {
        this.rules = rules;
        this.contextBuilder = contextBuilder;
        this.scoringProperties = scoringProperties;
    }

    public FraudAssessment evaluate(Transaction transaction) {
        Instant start = Instant.now();
        List<FraudRule> enabledRules = rules.stream()
                .filter(FraudRule::isEnabled)
                .sorted(Comparator.comparingInt(FraudRule::getPriority))
                .collect(Collectors.toList());

        log.debug("Starting rule evaluation: enabledRules={}", enabledRules.size());

        EvaluationContext context = contextBuilder.build(transaction);

        List<RuleResult> violations = enabledRules.stream()
                .map(rule -> rule.evaluate(transaction, context))
                .filter(RuleResult::isViolation)
                .collect(Collectors.toList());

        violations.forEach(v ->
                log.warn("Rule violated: rule={}, severity={}, detail={}",
                        v.getRuleName(), v.getSeverity(), v.getDescription()));

        double fraudProbability = calculateFraudProbability(violations);
        int riskScore = probabilityToRiskScore(fraudProbability);
        Disposition disposition;
        if (fraudProbability >= scoringProperties.getFraudProbabilityThreshold()) {
            disposition = Disposition.FLAGGED;
        } else if (fraudProbability >= scoringProperties.getReviewProbabilityThreshold()) {
            disposition = Disposition.PENDING_REVIEW;
        } else {
            disposition = Disposition.CLEARED;
        }

        FraudAssessment assessment = FraudAssessment.builder()
                .transaction(transaction)
                .disposition(disposition)
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

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        log.debug("Rule evaluation complete: disposition={}, riskScore={}, violations={}, elapsedMs={}",
                disposition, riskScore, violations.size(), elapsedMs);

        return assessment;
    }

    // Log-odds (naive-Bayes) combination — see ScoringProperties for rationale.
    // Treats each fired rule as evidence with a calibrated likelihood ratio and
    // combines them additively in log-space, which is the mathematically correct
    // way to combine (assumed-independent) probabilistic evidence — as opposed to
    // summing arbitrary point values, which the likelihood-ratio approach replaces.
    private double calculateFraudProbability(List<RuleResult> violations) {
        double prior = scoringProperties.getPriorFraudProbability();
        double logOdds = Math.log(prior / (1 - prior));

        for (RuleResult violation : violations) {
            logOdds += Math.log(likelihoodRatioFor(violation));
        }

        return 1.0 / (1.0 + Math.exp(-logOdds));
    }

    private double likelihoodRatioFor(RuleResult violation) {
        String key = violation.getRuleName() + ":" + violation.getSeverity().name();
        Double ratio = scoringProperties.getLikelihoodRatios().get(key);
        if (ratio != null) {
            return ratio;
        }
        log.warn("No calibrated likelihood ratio for '{}' — falling back to the {} severity default. "
                + "Add an explicit entry to fraud.scoring.likelihood-ratios.",
                key, violation.getSeverity());
        return scoringProperties.defaultLikelihoodRatioFor(violation.getSeverity());
    }

    private int probabilityToRiskScore(double probability) {
        int score = (int) Math.round(probability * 100);
        return Math.max(0, Math.min(score, 100));
    }

    public List<FraudRule> getRules() {
        return rules.stream()
                .sorted(Comparator.comparingInt(FraudRule::getPriority))
                .collect(Collectors.toList());
    }
}
