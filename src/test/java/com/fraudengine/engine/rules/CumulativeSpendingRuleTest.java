package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import com.fraudengine.model.enums.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CumulativeSpendingRuleTest {

    private CumulativeSpendingRule rule;
    // defaults: hourlyLimit=10000, dailyLimit=25000, hourlyWindowMinutes=60

    @BeforeEach
    void setUp() {
        RuleProperties props = new RuleProperties();
        props.getCumulativeSpending().setEnabled(true);
        props.getCumulativeSpending().setHourlyLimit(new BigDecimal("10000.00"));
        props.getCumulativeSpending().setDailyLimit(new BigDecimal("25000.00"));
        props.getCumulativeSpending().setHourlyWindowMinutes(60);
        rule = new CumulativeSpendingRule(props);
    }

    @Test
    void noHistory_belowBothLimits_passes() {
        assertThat(rule.evaluate(tx("500.00", Instant.now()), ctx(List.of(), "0.00")).isViolation()).isFalse();
    }

    @Test
    void hourlySpend_exceedsLimit_isViolation() {
        Instant now = Instant.now();
        // 9 prior transactions × 1100 = 9900 in hourly window + current 200 = 10100 → exceeds 10000
        List<Transaction> history = buildHistory(9, "1100.00", 5, now);
        RuleResult result = rule.evaluate(tx("200.00", now), ctx(history, "0.00"));
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(result.getRuleName()).isEqualTo("CUMULATIVE_SPENDING");
    }

    @Test
    void hourlySpend_exactlyAtLimit_passes() {
        // sum == limit is NOT a violation (> limit fires)
        Instant now = Instant.now();
        // 9 × 1000 + current 1000 = 10000 = hourly limit → should pass
        List<Transaction> history = buildHistory(9, "1000.00", 5, now);
        assertThat(rule.evaluate(tx("1000.00", now), ctx(history, "0.00")).isViolation()).isFalse();
    }

    @Test
    void dailySpend_exceedsLimit_isViolation() {
        // hourly is fine (current transaction alone), but daily total pushes over 25000
        Instant now = Instant.now();
        // daily spend already at 24900; current txn of 200 pushes to 25100
        RuleResult result = rule.evaluate(tx("200.00", now), ctx(List.of(), "24900.00"));
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void dailySpend_exactlyAtLimit_passes() {
        Instant now = Instant.now();
        // daily spend 24800 + current 200 = 25000 = limit → pass
        assertThat(rule.evaluate(tx("200.00", now), ctx(List.of(), "24800.00")).isViolation()).isFalse();
    }

    @Test
    void hourlyHistoryOutsideWindow_doesNotCountTowardsHourly() {
        Instant now = Instant.now();
        // 9 transactions 90 minutes ago (outside 60-min hourly window), large daily spend but hourly ok
        List<Transaction> oldHistory = buildHistory(9, "5000.00", 90, now);
        // daily spend = 9*5000 = 45000, but context was set to 0 to test hourly bypass
        // real scenario: dailySpendTotal would capture these; here we set dailySpendTotal low to isolate hourly
        assertThat(rule.evaluate(tx("500.00", now), ctx(oldHistory, "0.00")).isViolation()).isFalse();
    }

    @Test
    void hourlyCheckedBeforeDaily_hourlyFiresFirst() {
        Instant now = Instant.now();
        // Both limits will be exceeded; hourly should be detected first
        List<Transaction> history = buildHistory(5, "2500.00", 5, now);
        // hourly = 5*2500 + 200 = 12700 > 10000; daily = 24900 + 200 = 25100 > 25000
        RuleResult result = rule.evaluate(tx("200.00", now), ctx(history, "24900.00"));
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getDescription()).contains("Hourly");
    }

    @Test
    void disabledRule_isNotEnabled() {
        RuleProperties props = new RuleProperties();
        props.getCumulativeSpending().setEnabled(false);
        assertThat(new CumulativeSpendingRule(props).isEnabled()).isFalse();
    }

    @Test
    void bothLimitsOk_passes() {
        Instant now = Instant.now();
        List<Transaction> history = buildHistory(3, "500.00", 10, now);
        // hourly = 3*500 + 300 = 1800 < 10000; daily = 5000 + 300 = 5300 < 25000
        assertThat(rule.evaluate(tx("300.00", now), ctx(history, "5000.00")).isViolation()).isFalse();
    }

    private List<Transaction> buildHistory(int count, String amountEach, int minutesAgo, Instant base) {
        List<Transaction> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(Transaction.builder()
                    .id(UUID.randomUUID())
                    .customerId("CUST_CS")
                    .merchantId("MERCH_" + i)
                    .amount(new BigDecimal(amountEach))
                    .currency("ZAR")
                    .transactionType(TransactionType.CARD_PRESENT)
                    .timestamp(base.minus(minutesAgo + (long) i, ChronoUnit.MINUTES))
                    .build());
        }
        return list;
    }

    private Transaction tx(String amount, Instant ts) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId("CUST_CS").merchantId("MERCH_NEW")
                .amount(new BigDecimal(amount)).currency("ZAR")
                .transactionType(TransactionType.CARD_PRESENT).timestamp(ts).build();
    }

    private EvaluationContext ctx(List<Transaction> recent, String dailySpend) {
        return EvaluationContext.builder()
                .recentCustomerTransactions(recent)
                .dailySpendTotal(new BigDecimal(dailySpend))
                .build();
    }
}
