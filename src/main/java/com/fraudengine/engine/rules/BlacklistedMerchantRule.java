package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(4)
public class BlacklistedMerchantRule implements FraudRule {

    private static final String RULE_NAME = "BLACKLISTED_MERCHANT";
    private static final String RULE_VERSION = "1.0";

    private final RuleProperties properties;

    public BlacklistedMerchantRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, EvaluationContext context) {
        if (context.getBlacklistedMerchantIds().contains(transaction.getMerchantId())) {
            return RuleResult.violation(RULE_NAME, RULE_VERSION,
                    String.format("Transaction with blacklisted merchant: %s", transaction.getMerchantId()),
                    Severity.CRITICAL);
        }
        return RuleResult.pass(RULE_NAME);
    }

    @Override public String getRuleName()    { return RULE_NAME; }
    @Override public String getRuleVersion() { return RULE_VERSION; }
    @Override public int getPriority()       { return 4; }
    @Override public boolean isEnabled()     { return properties.getBlacklistedMerchant().isEnabled(); }
}
