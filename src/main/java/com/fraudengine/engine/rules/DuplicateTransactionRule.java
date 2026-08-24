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

@Component
@Order(3)
public class DuplicateTransactionRule implements FraudRule {

    private static final String RULE_NAME = "DUPLICATE_TRANSACTION";
    private static final String RULE_VERSION = "1.0";

    private final RuleProperties properties;

    public DuplicateTransactionRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, EvaluationContext context) {
        RuleProperties.DuplicateConfig config = properties.getDuplicate();

        // Physical channels (tap, insert, swipe, ATM) have tight windows.
        // CNP (online, MOTO) uses a wider window to catch payment processor retries.
        boolean isPhysicalChannel = transaction.getTransactionType() != TransactionType.CARD_NOT_PRESENT;
        int windowSeconds = isPhysicalChannel
                ? config.getCardPresentWindowSeconds()
                : config.getCardNotPresentWindowSeconds();

        Instant windowStart = transaction.getTimestamp().minus(windowSeconds, ChronoUnit.SECONDS);

        boolean hasDuplicate = context.getRecentCustomerTransactions().stream()
                .filter(t -> t.getTimestamp().isAfter(windowStart))
                .anyMatch(t -> t.getMerchantId().equals(transaction.getMerchantId())
                        && t.getAmount().compareTo(transaction.getAmount()) == 0
                        && t.getCurrency().equals(transaction.getCurrency()));

        if (hasDuplicate) {
            return RuleResult.violation(RULE_NAME, RULE_VERSION,
                    String.format("Duplicate transaction: same amount %s at merchant %s within %d seconds",
                            transaction.getAmount(), transaction.getMerchantId(), windowSeconds),
                    Severity.CRITICAL);
        }
        return RuleResult.pass(RULE_NAME);
    }

    @Override public String getRuleName()    { return RULE_NAME; }
    @Override public String getRuleVersion() { return RULE_VERSION; }
    @Override public int getPriority()       { return 3; }
    @Override public boolean isEnabled()     { return properties.getDuplicate().isEnabled(); }

}
