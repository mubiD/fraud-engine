package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TimeOfDayAnomalyRuleTest {

    private TimeOfDayAnomalyRule rule;
    private EvaluationContext emptyContext;

    @BeforeEach
    void setUp() {
        RuleProperties props = new RuleProperties();
        props.getTimeOfDay().setEnabled(true);
        props.getTimeOfDay().setOffHoursStartHour(23);
        props.getTimeOfDay().setOffHoursEndHour(5);
        rule = new TimeOfDayAnomalyRule(props);
        emptyContext = EvaluationContext.builder()
                .recentCustomerTransactions(List.of())
                .build();
    }

    @Test
    void midnightTransaction_isViolation() {
        RuleResult result = rule.evaluate(tx(Instant.parse("2026-07-15T00:30:00Z")), emptyContext);
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getRuleName()).isEqualTo("TIME_OF_DAY_ANOMALY");
        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void earlyMorningTransaction_isViolation() {
        RuleResult result = rule.evaluate(tx(Instant.parse("2026-07-15T03:00:00Z")), emptyContext);
        assertThat(result.isViolation()).isTrue();
    }

    @Test
    void justBeforeWindowStart_passes() {
        RuleResult result = rule.evaluate(tx(Instant.parse("2026-07-15T22:59:00Z")), emptyContext);
        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void windowStartHour_isViolation() {
        // 23:00 UTC — exactly at window start (inclusive)
        RuleResult result = rule.evaluate(tx(Instant.parse("2026-07-15T23:00:00Z")), emptyContext);
        assertThat(result.isViolation()).isTrue();
    }

    @Test
    void windowEndHour_passes() {
        // 05:00 UTC — exactly at window end (exclusive)
        RuleResult result = rule.evaluate(tx(Instant.parse("2026-07-15T05:00:00Z")), emptyContext);
        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void businessHoursTransaction_passes() {
        RuleResult result = rule.evaluate(tx(Instant.parse("2026-07-15T10:00:00Z")), emptyContext);
        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void afternoonTransaction_passes() {
        RuleResult result = rule.evaluate(tx(Instant.parse("2026-07-15T14:00:00Z")), emptyContext);
        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void nonMidnightWrappingWindow_worksCorrectly() {
        // Window 08:00–12:00 — does NOT span midnight
        RuleProperties props = new RuleProperties();
        props.getTimeOfDay().setOffHoursStartHour(8);
        props.getTimeOfDay().setOffHoursEndHour(12);
        TimeOfDayAnomalyRule customRule = new TimeOfDayAnomalyRule(props);

        assertThat(customRule.evaluate(tx(Instant.parse("2026-07-15T10:00:00Z")), emptyContext).isViolation()).isTrue();
        assertThat(customRule.evaluate(tx(Instant.parse("2026-07-15T07:00:00Z")), emptyContext).isViolation()).isFalse();
        assertThat(customRule.evaluate(tx(Instant.parse("2026-07-15T13:00:00Z")), emptyContext).isViolation()).isFalse();
    }

    @Test
    void disabledRule_isEnabled_returnsFalse() {
        RuleProperties props = new RuleProperties();
        props.getTimeOfDay().setEnabled(false);
        assertThat(new TimeOfDayAnomalyRule(props).isEnabled()).isFalse();
    }

    private Transaction tx(Instant timestamp) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId("CUST_001").merchantId("MERCH_001")
                .amount(new BigDecimal("250.00")).currency("ZAR")
                .timestamp(timestamp).build();
    }
}
