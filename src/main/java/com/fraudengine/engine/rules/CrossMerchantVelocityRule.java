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
@Order(11)
public class CrossMerchantVelocityRule implements FraudRule {

    private static final String RULE_NAME = "CROSS_MERCHANT_VELOCITY";
    private static final String RULE_VERSION = "1.0";

    private final RuleProperties properties;

    public CrossMerchantVelocityRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, EvaluationContext context) {
        RuleProperties.CrossMerchantVelocityConfig cfg = properties.getCrossMerchantVelocity();
        Instant windowStart = transaction.getTimestamp().minus(cfg.getWindowMinutes(), ChronoUnit.MINUTES);

        long txnCount = context.getRecentCustomerTransactions().stream()
                .filter(t -> t.getTimestamp().isAfter(windowStart))
                .count() + 1; // +1 for the current transaction

        if (txnCount >= cfg.getMaxTransactions()) {
            return RuleResult.violation(RULE_NAME, RULE_VERSION,
                    String.format("%d transactions across merchants in %d minutes",
                            txnCount, cfg.getWindowMinutes()),
                    Severity.MEDIUM);
        }
        return RuleResult.pass(RULE_NAME);
    }

    @Override public String getRuleName()    { return RULE_NAME; }
    @Override public String getRuleVersion() { return RULE_VERSION; }
    @Override public int getPriority()       { return 11; }
    @Override public boolean isEnabled()     { return properties.getCrossMerchantVelocity().isEnabled(); }

    @Override
    public java.util.Map<String, Object> getConfig() {
        RuleProperties.CrossMerchantVelocityConfig c = properties.getCrossMerchantVelocity();
        return java.util.Map.of("windowMinutes", c.getWindowMinutes(), "maxTransactions", c.getMaxTransactions());
    }
}
