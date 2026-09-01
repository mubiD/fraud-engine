package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(13)
public class CustomerAmountAnomalyRule implements FraudRule {

    private static final String RULE_NAME = "CUSTOMER_AMOUNT_ANOMALY";
    private static final String RULE_VERSION = "1.0";

    private final RuleProperties properties;

    public CustomerAmountAnomalyRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, EvaluationContext context) {
        RuleProperties.CustomerAmountAnomalyConfig config = properties.getCustomerAmountAnomaly();
        List<Transaction> history = context.getCustomerBaselineTransactions();

        if (history.size() < config.getMinHistoryCount()) {
            return RuleResult.pass(RULE_NAME);
        }

        double mean = history.stream().mapToDouble(t -> t.getAmount().doubleValue()).average().orElse(0);
        // Population variance (divide by n, not n-1) — a heuristic anomaly signal, not a
        // financial calculation, so the small-sample correction isn't worth the complexity.
        double variance = history.stream()
                .mapToDouble(t -> Math.pow(t.getAmount().doubleValue() - mean, 2))
                .average().orElse(0);
        double stdDev = Math.sqrt(variance);

        // All prior amounts identical (e.g. a subscription-only customer) — no meaningful
        // z-score can be computed. Known limitation: skip rather than divide by zero.
        if (stdDev == 0) {
            return RuleResult.pass(RULE_NAME);
        }

        double zScore = (transaction.getAmount().doubleValue() - mean) / stdDev;
        if (zScore > config.getStddevMultiplier()) {
            return RuleResult.violation(RULE_NAME, RULE_VERSION,
                    String.format("Transaction amount %s %s is %.1f standard deviations above customer's %d-day average of %.2f (n=%d)",
                            transaction.getAmount(), transaction.getCurrency(), zScore,
                            config.getLookbackDays(), mean, history.size()),
                    Severity.MEDIUM);
        }
        return RuleResult.pass(RULE_NAME);
    }

    @Override public String getRuleName()    { return RULE_NAME; }
    @Override public String getRuleVersion() { return RULE_VERSION; }
    @Override public int getPriority()       { return 13; }
    @Override public boolean isEnabled()     { return properties.getCustomerAmountAnomaly().isEnabled(); }
}
