package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Component
public class CumulativeSpendingRule implements FraudRule {

    private static final String RULE_NAME = "CUMULATIVE_SPENDING";
    private static final String RULE_VERSION = "1.0";

    private final RuleProperties properties;

    public CumulativeSpendingRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, EvaluationContext context) {
        RuleProperties.CumulativeSpendingConfig cfg = properties.getCumulativeSpending();
        BigDecimal currentAmount = transaction.getAmount();
        String currency = transaction.getCurrency();

        // Hourly: sum transactions in the rolling window from recent context, restricted to
        // this transaction's own currency: recentCustomerTransactions can hold more than
        // one currency for a customer, and pooling them as equivalent magnitude would be
        // wrong (a real gap here previously; context.getDailySpendTotal() below is already
        // currency-scoped at the source by EvaluationContextBuilder).
        Instant hourlyWindowStart = transaction.getTimestamp()
                .minus(cfg.getHourlyWindowMinutes(), ChronoUnit.MINUTES);
        BigDecimal hourlySpend = context.getRecentCustomerTransactions().stream()
                .filter(t -> t.getTimestamp().isAfter(hourlyWindowStart))
                .filter(t -> currency.equals(t.getCurrency()))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(currentAmount);

        if (hourlySpend.compareTo(cfg.getHourlyLimit()) > 0) {
            return RuleResult.violation(RULE_NAME, RULE_VERSION,
                    String.format("Hourly spend %.2f %s exceeds limit %.2f %s",
                            hourlySpend, currency, cfg.getHourlyLimit(), currency),
                    Severity.HIGH);
        }

        // Daily: pre-computed 24h aggregate from EvaluationContextBuilder + current transaction
        BigDecimal dailySpend = context.getDailySpendTotal().add(currentAmount);
        if (dailySpend.compareTo(cfg.getDailyLimit()) > 0) {
            return RuleResult.violation(RULE_NAME, RULE_VERSION,
                    String.format("Daily spend %.2f %s exceeds limit %.2f %s",
                            dailySpend, currency, cfg.getDailyLimit(), currency),
                    Severity.HIGH);
        }

        return RuleResult.pass(RULE_NAME);
    }

    @Override public String getRuleName()    { return RULE_NAME; }
    @Override public String getRuleVersion() { return RULE_VERSION; }
    @Override public int getPriority()       { return 12; }
    @Override public boolean isEnabled()     { return properties.getCumulativeSpending().isEnabled(); }

    @Override
    public Map<String, Object> getConfig() {
        return Map.of(
                "hourlyLimit", properties.getCumulativeSpending().getHourlyLimit(),
                "dailyLimit", properties.getCumulativeSpending().getDailyLimit(),
                "hourlyWindowMinutes", properties.getCumulativeSpending().getHourlyWindowMinutes());
    }

}
