package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@Order(2)
public class VelocityRule implements FraudRule {

    private static final String RULE_NAME = "VELOCITY";
    private static final String RULE_VERSION = "1.0";

    private final RuleProperties properties;

    public VelocityRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, EvaluationContext context) {
        RuleProperties.VelocityConfig config = properties.getVelocity();
        Instant windowStart = transaction.getTimestamp()
                .minus(config.getWindowMinutes(), ChronoUnit.MINUTES);

        long count = context.getRecentCustomerTransactions().stream()
                .filter(t -> t.getTimestamp().isAfter(windowStart))
                .count();

        if (count >= config.getMaxTransactions()) {
            Severity severity = isHighRiskCategory(transaction.getCategory())
                    ? Severity.CRITICAL : Severity.HIGH;
            String suffix = severity == Severity.CRITICAL
                    ? " — escalated to CRITICAL due to high-risk merchant category" : "";
            return RuleResult.violation(RULE_NAME, RULE_VERSION,
                    String.format("Customer %s made %d transactions in %d minutes (max: %d)%s",
                            transaction.getCustomerId(), count + 1,
                            config.getWindowMinutes(), config.getMaxTransactions(), suffix),
                    severity);
        }
        return RuleResult.pass(RULE_NAME);
    }

    private boolean isHighRiskCategory(String category) {
        if (category == null || category.isBlank()) return false;
        String upper = category.toUpperCase();
        return properties.getHighRiskCategory().getHighRiskKeywords().stream()
                .anyMatch(kw -> upper.contains(kw.toUpperCase()));
    }

    @Override public String getRuleName()    { return RULE_NAME; }
    @Override public String getRuleVersion() { return RULE_VERSION; }
    @Override public int getPriority()       { return 2; }
    @Override public boolean isEnabled()     { return properties.getVelocity().isEnabled(); }

    @Override
    public java.util.Map<String, Object> getConfig() {
        RuleProperties.VelocityConfig c = properties.getVelocity();
        return java.util.Map.of("windowMinutes", c.getWindowMinutes(), "maxTransactions", c.getMaxTransactions());
    }
}
