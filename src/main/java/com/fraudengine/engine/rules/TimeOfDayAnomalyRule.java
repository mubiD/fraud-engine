package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;

/**
 * Flags transactions that fall within a configurable off-hours window (UTC).
 * Transactions at 23:00–05:00 UTC are statistically higher-risk and warrant
 * additional scrutiny. Severity is MEDIUM so the rule contributes to the risk
 * score without standalone-flagging ordinary night-shift purchases.
 */
@Component
@Order(7)
public class TimeOfDayAnomalyRule implements FraudRule {

    private static final String RULE_NAME = "TIME_OF_DAY_ANOMALY";
    private static final String RULE_VERSION = "1.0";

    private final RuleProperties properties;

    public TimeOfDayAnomalyRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, EvaluationContext context) {
        RuleProperties.TimeOfDayConfig config = properties.getTimeOfDay();
        int hour = transaction.getTimestamp().atZone(ZoneOffset.UTC).getHour();
        int start = config.getOffHoursStartHour();
        int end   = config.getOffHoursEndHour();

        // Handle windows that span midnight (e.g. 23:00 → 05:00)
        boolean isOffHours = start > end
                ? hour >= start || hour < end
                : hour >= start && hour < end;

        if (isOffHours) {
            return RuleResult.violation(RULE_NAME, RULE_VERSION,
                    String.format(
                            "Transaction at %02d:00 UTC falls within off-hours window (%02d:00–%02d:00 UTC)",
                            hour, start, end),
                    Severity.MEDIUM);
        }
        return RuleResult.pass(RULE_NAME);
    }

    @Override public String getRuleName()    { return RULE_NAME; }
    @Override public String getRuleVersion() { return RULE_VERSION; }
    @Override public int getPriority()       { return 7; }
    @Override public boolean isEnabled()     { return properties.getTimeOfDay().isEnabled(); }

    @Override
    public java.util.Map<String, Object> getConfig() {
        RuleProperties.TimeOfDayConfig c = properties.getTimeOfDay();
        return java.util.Map.of("offHoursStartHour", c.getOffHoursStartHour(), "offHoursEndHour", c.getOffHoursEndHour());
    }
}
