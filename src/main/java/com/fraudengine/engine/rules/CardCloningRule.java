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

/**
 * Detects card cloning by finding the same amount charged to multiple distinct
 * merchants within a short window — a hallmark of automated card testing with
 * a cloned card.
 */
@Component
@Order(6)
public class CardCloningRule implements FraudRule {

    private static final String RULE_NAME = "CARD_CLONING";
    private static final String RULE_VERSION = "1.0";

    private final RuleProperties properties;

    public CardCloningRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, EvaluationContext context) {
        RuleProperties.CardCloningConfig config = properties.getCardCloning();
        Instant windowStart = transaction.getTimestamp()
                .minus(config.getWindowMinutes(), ChronoUnit.MINUTES);

        long distinctOtherMerchantsWithSameAmount = context.getRecentCustomerTransactions().stream()
                .filter(t -> t.getTimestamp().isAfter(windowStart))
                .filter(t -> t.getAmount().compareTo(transaction.getAmount()) == 0)
                .filter(t -> !t.getMerchantId().equals(transaction.getMerchantId()))
                .map(Transaction::getMerchantId)
                .distinct()
                .count();

        if (distinctOtherMerchantsWithSameAmount >= config.getMinDifferentMerchants()) {
            return RuleResult.violation(RULE_NAME, RULE_VERSION,
                    String.format(
                            "Card cloning pattern: amount %s %s repeated at %d different merchants within %d minutes",
                            transaction.getAmount(), transaction.getCurrency(),
                            distinctOtherMerchantsWithSameAmount + 1,
                            config.getWindowMinutes()),
                    Severity.MEDIUM);
        }
        return RuleResult.pass(RULE_NAME);
    }

    @Override public String getRuleName()    { return RULE_NAME; }
    @Override public String getRuleVersion() { return RULE_VERSION; }
    @Override public int getPriority()       { return 6; }
    @Override public boolean isEnabled()     { return properties.getCardCloning().isEnabled(); }

    @Override
    public java.util.Map<String, Object> getConfig() {
        RuleProperties.CardCloningConfig c = properties.getCardCloning();
        return java.util.Map.of("windowMinutes", c.getWindowMinutes(), "minDifferentMerchants", c.getMinDifferentMerchants());
    }
}
