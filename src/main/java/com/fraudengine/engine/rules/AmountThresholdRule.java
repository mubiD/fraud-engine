package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class AmountThresholdRule implements FraudRule {

    private static final String RULE_NAME = "AMOUNT_THRESHOLD";
    private static final String RULE_VERSION = "1.0";

    private final RuleProperties properties;

    public AmountThresholdRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, EvaluationContext context) {
        BigDecimal threshold = properties.getAmountThreshold().effectiveThreshold(transaction.getCategory());
        if (transaction.getAmount().compareTo(threshold) > 0) {
            String categoryLabel = transaction.getCategory() != null && !transaction.getCategory().isBlank()
                    ? transaction.getCategory() : "default";
            return RuleResult.violation(RULE_NAME, RULE_VERSION,
                    String.format("Transaction amount %s %s exceeds %s threshold %s",
                            transaction.getAmount(), transaction.getCurrency(), categoryLabel, threshold),
                    Severity.HIGH);
        }
        return RuleResult.pass(RULE_NAME);
    }

    @Override public String getRuleName()    { return RULE_NAME; }
    @Override public String getRuleVersion() { return RULE_VERSION; }
    @Override public int getPriority()       { return 1; }
    @Override public boolean isEnabled()     { return properties.getAmountThreshold().isEnabled(); }

    @Override
    public Map<String, Object> getConfig() {
        return Map.of(
                "threshold", properties.getAmountThreshold().getThreshold(),
                "categoryThresholds", properties.getAmountThreshold().getCategoryThresholds());
    }

}
