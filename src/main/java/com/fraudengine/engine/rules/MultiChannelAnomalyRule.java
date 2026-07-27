package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import com.fraudengine.model.enums.TransactionType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Flags rapid channel-switching between physical (CARD_PRESENT, CONTACTLESS, ATM)
 * and card-not-present transactions within a short window. A legitimate customer
 * cannot tap in-store and simultaneously transact online from a different location —
 * this pattern suggests a second actor has obtained the card details.
 */
@Component
@Order(10)
public class MultiChannelAnomalyRule implements FraudRule {

    private static final String RULE_NAME = "MULTI_CHANNEL_ANOMALY";
    private static final String RULE_VERSION = "1.0";

    private final RuleProperties properties;

    public MultiChannelAnomalyRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, EvaluationContext context) {
        RuleProperties.MultiChannelConfig config = properties.getMultiChannel();
        Instant windowStart = transaction.getTimestamp()
                .minus(config.getWindowMinutes(), ChronoUnit.MINUTES);

        boolean currentIsPhysical = isPhysical(transaction.getTransactionType());

        boolean conflictFound = context.getRecentCustomerTransactions().stream()
                .filter(t -> t.getTimestamp().isAfter(windowStart))
                .anyMatch(t -> isPhysical(t.getTransactionType()) != currentIsPhysical);

        if (conflictFound) {
            String currentChannel  = currentIsPhysical ? "physical" : "card-not-present";
            String conflictChannel = currentIsPhysical ? "card-not-present" : "physical";
            return RuleResult.violation(RULE_NAME, RULE_VERSION,
                    String.format(
                            "Rapid channel switch: %s transaction follows a %s transaction within %d minutes",
                            currentChannel, conflictChannel, config.getWindowMinutes()),
                    Severity.MEDIUM);
        }
        return RuleResult.pass(RULE_NAME);
    }

    private boolean isPhysical(TransactionType type) {
        return type == TransactionType.CARD_PRESENT
                || type == TransactionType.CONTACTLESS
                || type == TransactionType.ATM;
    }

    @Override public String getRuleName()    { return RULE_NAME; }
    @Override public String getRuleVersion() { return RULE_VERSION; }
    @Override public int getPriority()       { return 10; }
    @Override public boolean isEnabled()     { return properties.getMultiChannel().isEnabled(); }
}
