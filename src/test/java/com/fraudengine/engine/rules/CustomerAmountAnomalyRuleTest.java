package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.AmountBaselineStats;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerAmountAnomalyRuleTest {

    private CustomerAmountAnomalyRule rule;
    private RuleProperties props;

    @BeforeEach
    void setUp() {
        props = new RuleProperties();
        props.getCustomerAmountAnomaly().setEnabled(true);
        props.getCustomerAmountAnomaly().setLookbackDays(90);
        props.getCustomerAmountAnomaly().setMinHistoryCount(5);
        props.getCustomerAmountAnomaly().setStddevMultiplier(3.0);
        rule = new CustomerAmountAnomalyRule(props);
    }

    @Test
    void historyBelowMinCount_passes() {
        List<Transaction> history = uniformHistory(4, "80.00");
        Transaction tx = transaction("5000.00");

        RuleResult result = rule.evaluate(tx, context(history));

        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void allIdenticalHistory_degenerateVariance_passes() {
        // stdDev == 0 — no meaningful z-score can be computed, known limitation
        List<Transaction> history = uniformHistory(10, "100.00");
        Transaction tx = transaction("500.00");

        RuleResult result = rule.evaluate(tx, context(history));

        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void withinStddevMultiplier_passes() {
        // Mean 80, discrete-uniform 78-82 -> stdDev ~1.41, so 3 stdDev ~4.24;
        // an 84.00 transaction (deviation 4) is just within that.
        List<Transaction> history = varyingHistory(10, 78, 82);
        Transaction tx = transaction("84.00");

        RuleResult result = rule.evaluate(tx, context(history));

        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void beyondStddevMultiplier_isViolation() {
        // Mean ~80, tight variance — an 800.00 transaction is far beyond 3 stddev
        List<Transaction> history = varyingHistory(10, 78, 82);
        Transaction tx = transaction("800.00");

        RuleResult result = rule.evaluate(tx, context(history));

        assertThat(result.isViolation()).isTrue();
        assertThat(result.getRuleName()).isEqualTo("CUSTOMER_AMOUNT_ANOMALY");
        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(result.getDescription()).contains("standard deviations above customer's");
    }

    @Test
    void disabledRule_isEnabled_returnsFalse() {
        RuleProperties disabledProps = new RuleProperties();
        disabledProps.getCustomerAmountAnomaly().setEnabled(false);
        assertThat(new CustomerAmountAnomalyRule(disabledProps).isEnabled()).isFalse();
    }

    private EvaluationContext context(List<Transaction> baselineHistory) {
        return EvaluationContext.builder()
                .recentCustomerTransactions(List.of())
                .customerAmountBaseline(AmountBaselineStats.from(baselineHistory))
                .build();
    }

    private List<Transaction> uniformHistory(int count, String amount) {
        List<Transaction> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(historyTx(amount, i));
        }
        return history;
    }

    private List<Transaction> varyingHistory(int count, int lowInclusive, int highInclusive) {
        List<Transaction> history = new ArrayList<>();
        int span = highInclusive - lowInclusive + 1;
        for (int i = 0; i < count; i++) {
            int amount = lowInclusive + (i % span);
            history.add(historyTx(amount + ".00", i));
        }
        return history;
    }

    private Transaction historyTx(String amount, int daysAgo) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId("CUST_001").merchantId("MERCH_" + daysAgo)
                .amount(new BigDecimal(amount)).currency("ZAR")
                .timestamp(Instant.now().minus(daysAgo + 1L, ChronoUnit.DAYS))
                .build();
    }

    private Transaction transaction(String amount) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId("CUST_001").merchantId("MERCH_NEW")
                .amount(new BigDecimal(amount)).currency("ZAR").timestamp(Instant.now())
                .build();
    }
}
