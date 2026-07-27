package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AmountThresholdRuleTest {

    private AmountThresholdRule rule;
    private EvaluationContext emptyContext;

    @BeforeEach
    void setUp() {
        RuleProperties props = new RuleProperties();
        props.getAmountThreshold().setThreshold(new BigDecimal("5000.00"));
        props.getAmountThreshold().setCategoryThresholds(Map.of(
                "RETAIL", new BigDecimal("15000.00"),
                "ELECTRONICS", new BigDecimal("15000.00"),
                "TRAVEL", new BigDecimal("20000.00"),
                "GROCERY", new BigDecimal("3000.00"),
                "MONEY_TRANSFER", new BigDecimal("2000.00"),
                "WIRE_TRANSFER", new BigDecimal("2000.00")
        ));
        props.getAmountThreshold().setEnabled(true);
        rule = new AmountThresholdRule(props);
        emptyContext = EvaluationContext.builder()
                .recentCustomerTransactions(List.of())
                .blacklistedMerchantIds(Set.of())
                .build();
    }

    // ---- Default threshold (no category) ----

    @Test
    void belowDefaultThreshold_passes() {
        assertThat(rule.evaluate(transaction(new BigDecimal("4999.99"), null), emptyContext).isViolation()).isFalse();
    }

    @Test
    void exactlyAtDefaultThreshold_passes() {
        assertThat(rule.evaluate(transaction(new BigDecimal("5000.00"), null), emptyContext).isViolation()).isFalse();
    }

    @Test
    void aboveDefaultThreshold_isViolation() {
        RuleResult result = rule.evaluate(transaction(new BigDecimal("5000.01"), null), emptyContext);
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getRuleName()).isEqualTo("AMOUNT_THRESHOLD");
    }

    // ---- Category with raised threshold (RETAIL: 15 000) ----

    @Test
    void retail_belowRaisedThreshold_passes() {
        // 14 999 ZAR TV — legitimate large retail purchase
        assertThat(rule.evaluate(transaction(new BigDecimal("14999.99"), "RETAIL"), emptyContext).isViolation()).isFalse();
    }

    @Test
    void retail_aboveRaisedThreshold_isViolation() {
        assertThat(rule.evaluate(transaction(new BigDecimal("15000.01"), "RETAIL"), emptyContext).isViolation()).isTrue();
    }

    @Test
    void retail_wouldHaveTriggeredDefaultButPasses() {
        // 5500 ZAR is above the default (5000) but below RETAIL limit (15 000) — must NOT flag
        assertThat(rule.evaluate(transaction(new BigDecimal("5500.00"), "RETAIL"), emptyContext).isViolation()).isFalse();
    }

    @Test
    void travel_aboveRaisedThreshold_isViolation() {
        assertThat(rule.evaluate(transaction(new BigDecimal("20000.01"), "TRAVEL"), emptyContext).isViolation()).isTrue();
    }

    // ---- Category with lowered threshold (MONEY_TRANSFER: 2 000) ----

    @Test
    void moneyTransfer_aboveLoweredThreshold_isViolation() {
        // 2 500 ZAR — below default but above MONEY_TRANSFER limit
        RuleResult result = rule.evaluate(transaction(new BigDecimal("2500.00"), "MONEY_TRANSFER"), emptyContext);
        assertThat(result.isViolation()).isTrue();
    }

    @Test
    void moneyTransfer_belowLoweredThreshold_passes() {
        assertThat(rule.evaluate(transaction(new BigDecimal("1999.99"), "MONEY_TRANSFER"), emptyContext).isViolation()).isFalse();
    }

    @Test
    void grocery_aboveLoweredThreshold_isViolation() {
        assertThat(rule.evaluate(transaction(new BigDecimal("3500.00"), "GROCERY"), emptyContext).isViolation()).isTrue();
    }

    // ---- Case-insensitive category lookup ----

    @Test
    void categoryLookup_isCaseInsensitive() {
        assertThat(rule.evaluate(transaction(new BigDecimal("5500.00"), "retail"), emptyContext).isViolation()).isFalse();
        assertThat(rule.evaluate(transaction(new BigDecimal("5500.00"), "Retail"), emptyContext).isViolation()).isFalse();
    }

    // ---- Unknown category falls back to default ----

    @Test
    void unknownCategory_fallsBackToDefault() {
        assertThat(rule.evaluate(transaction(new BigDecimal("5500.00"), "UNKNOWN_CATEGORY"), emptyContext).isViolation()).isTrue();
    }

    @Test
    void disabledRule_isEnabled_returnsFalse() {
        RuleProperties props = new RuleProperties();
        props.getAmountThreshold().setEnabled(false);
        assertThat(new AmountThresholdRule(props).isEnabled()).isFalse();
    }

    private Transaction transaction(BigDecimal amount, String category) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId("CUST_001").merchantId("MERCH_001")
                .amount(amount).currency("ZAR").category(category).timestamp(Instant.now()).build();
    }
}
