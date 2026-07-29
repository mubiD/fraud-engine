package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Scores transactions based on the risk tier of the merchant category.
 * HIGH tier (crypto exchanges, money transfers) contributes 50 pts — enough
 * to trigger a fraud verdict alone. MEDIUM tier (gambling, payday loans)
 * contributes 25 pts as an additive signal alongside other rules.
 */
@Component
@Order(8)
public class HighRiskMerchantCategoryRule implements FraudRule {

    private static final String RULE_NAME = "HIGH_RISK_MERCHANT_CATEGORY";
    private static final String RULE_VERSION = "1.0";

    private final RuleProperties properties;

    public HighRiskMerchantCategoryRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, EvaluationContext context) {
        if (transaction.getCategory() == null || transaction.getCategory().isBlank()) {
            return RuleResult.pass(RULE_NAME);
        }

        RuleProperties.HighRiskCategoryConfig config = properties.getHighRiskCategory();
        String category = transaction.getCategory().toUpperCase();

        for (String keyword : config.getHighRiskKeywords()) {
            if (category.contains(keyword.toUpperCase())) {
                return RuleResult.violation(RULE_NAME, RULE_VERSION,
                        String.format("Transaction category '%s' matches high-risk keyword '%s'",
                                transaction.getCategory(), keyword),
                        Severity.HIGH);
            }
        }

        for (String keyword : config.getMediumRiskKeywords()) {
            if (category.contains(keyword.toUpperCase())) {
                return RuleResult.violation(RULE_NAME, RULE_VERSION,
                        String.format("Transaction category '%s' matches elevated-risk keyword '%s'",
                                transaction.getCategory(), keyword),
                        Severity.MEDIUM);
            }
        }

        return RuleResult.pass(RULE_NAME);
    }

    @Override public String getRuleName()    { return RULE_NAME; }
    @Override public String getRuleVersion() { return RULE_VERSION; }
    @Override public int getPriority()       { return 8; }
    @Override public boolean isEnabled()     { return properties.getHighRiskCategory().isEnabled(); }

    @Override
    public java.util.Map<String, Object> getConfig() {
        RuleProperties.HighRiskCategoryConfig c = properties.getHighRiskCategory();
        return java.util.Map.of(
            "highRiskKeywords", c.getHighRiskKeywords(),
            "mediumRiskKeywords", c.getMediumRiskKeywords()
        );
    }
}
