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

class CrossMerchantVelocityRuleTest {

    private CrossMerchantVelocityRule rule;

    @BeforeEach
    void setUp() {
        RuleProperties props = new RuleProperties();
        props.getCrossMerchantVelocity().setEnabled(true);
        props.getCrossMerchantVelocity().setMaxTransactions(10);
        props.getCrossMerchantVelocity().setWindowMinutes(10);
        rule = new CrossMerchantVelocityRule(props);
    }

    @Test
    void noHistory_passes() {
        RuleResult result = rule.evaluate(tx(Instant.now()), ctx(List.of()));
        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void nineHistoricalPlusCurrent_exactlyAtThreshold_isViolation() {
        // 9 in history + 1 current = 10 → fires at >= 10
        Instant now = Instant.now();
        List<Transaction> history = buildHistory(9, 1, now);
        RuleResult result = rule.evaluate(tx(now), ctx(history));
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(result.getRuleName()).isEqualTo("CROSS_MERCHANT_VELOCITY");
    }

    @Test
    void eightHistoricalPlusCurrent_belowThreshold_passes() {
        // 8 in history + 1 current = 9 → no fire
        Instant now = Instant.now();
        List<Transaction> history = buildHistory(8, 1, now);
        assertThat(rule.evaluate(tx(now), ctx(history)).isViolation()).isFalse();
    }

    @Test
    void fifteenHistorical_aboveThreshold_isViolation() {
        Instant now = Instant.now();
        List<Transaction> history = buildHistory(15, 1, now);
        assertThat(rule.evaluate(tx(now), ctx(history)).isViolation()).isTrue();
    }

    @Test
    void historyOutsideWindow_doesNotCount() {
        Instant now = Instant.now();
        // 9 historical transactions all older than the 10-minute window
        List<Transaction> oldHistory = buildHistory(9, 11, now); // 11 minutes apart → outside window
        assertThat(rule.evaluate(tx(now), ctx(oldHistory)).isViolation()).isFalse();
    }

    @Test
    void mixedWindowHistory_onlyCountsWithinWindow() {
        Instant now = Instant.now();
        List<Transaction> history = new ArrayList<>();
        // 5 within window
        history.addAll(buildHistory(5, 1, now));
        // 10 outside window
        history.addAll(buildHistory(10, 11, now));
        // 5 in window + 1 current = 6 → no fire
        assertThat(rule.evaluate(tx(now), ctx(history)).isViolation()).isFalse();
    }

    @Test
    void disabledRule_isNotEnabled() {
        RuleProperties props = new RuleProperties();
        props.getCrossMerchantVelocity().setEnabled(false);
        assertThat(new CrossMerchantVelocityRule(props).isEnabled()).isFalse();
    }

    @Test
    void customThreshold_fires() {
        RuleProperties props = new RuleProperties();
        props.getCrossMerchantVelocity().setMaxTransactions(3);
        props.getCrossMerchantVelocity().setWindowMinutes(10);
        CrossMerchantVelocityRule customRule = new CrossMerchantVelocityRule(props);
        Instant now = Instant.now();
        // 2 historical + 1 current = 3 → fires
        assertThat(customRule.evaluate(tx(now), ctx(buildHistory(2, 1, now))).isViolation()).isTrue();
    }

    // Build `count` transactions spaced `intervalMinutes` apart, going backwards from `base`
    private List<Transaction> buildHistory(int count, int intervalMinutes, Instant base) {
        List<Transaction> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(Transaction.builder()
                    .id(UUID.randomUUID())
                    .customerId("CUST_CMV")
                    .merchantId("MERCH_" + i)
                    .amount(new BigDecimal("100.00"))
                    .currency("ZAR")
                    .transactionType(TransactionType.CARD_PRESENT)
                    .timestamp(base.minus((long) i * intervalMinutes, ChronoUnit.MINUTES))
                    .build());
        }
        return list;
    }

    private Transaction tx(Instant ts) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId("CUST_CMV").merchantId("MERCH_CURRENT")
                .amount(new BigDecimal("100.00")).currency("ZAR")
                .transactionType(TransactionType.CARD_PRESENT).timestamp(ts).build();
    }

    private EvaluationContext ctx(List<Transaction> recent) {
        return EvaluationContext.builder()
                .recentCustomerTransactions(recent)
                .build();
    }
}
