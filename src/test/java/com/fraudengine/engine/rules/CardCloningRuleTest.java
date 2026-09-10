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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardCloningRuleTest {

    private CardCloningRule rule;

    @BeforeEach
    void setUp() {
        RuleProperties props = new RuleProperties();
        props.getCardCloning().setEnabled(true);
        props.getCardCloning().setWindowMinutes(10);
        props.getCardCloning().setMinDifferentMerchants(2);
        rule = new CardCloningRule(props);
    }

    @Test
    void noHistory_passes() {
        RuleResult result = rule.evaluate(tx("MERCH_A", new BigDecimal("99.99"), Instant.now()), ctx(List.of()));
        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void sameAmountOneDifferentMerchant_passes() {
        Instant now = Instant.now();
        // Only 1 other merchant, below the min-different-merchants threshold of 2
        RuleResult result = rule.evaluate(
                tx("MERCH_B", new BigDecimal("99.99"), now),
                ctx(List.of(tx("MERCH_A", new BigDecimal("99.99"), now.minus(3, ChronoUnit.MINUTES)))));
        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void sameAmountTwoDifferentMerchants_isViolation() {
        Instant now = Instant.now();
        RuleResult result = rule.evaluate(
                tx("ALIEXPRESS", new BigDecimal("99.99"), now),
                ctx(List.of(
                        tx("AMAZON", new BigDecimal("99.99"), now.minus(5, ChronoUnit.MINUTES)),
                        tx("EBAY",   new BigDecimal("99.99"), now.minus(3, ChronoUnit.MINUTES)))));
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getRuleName()).isEqualTo("CARD_CLONING");
        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void sameAmountSameMerchantRepeated_passes() {
        Instant now = Instant.now();
        // History has same merchant as current transaction: not cloning, could be duplicate
        RuleResult result = rule.evaluate(
                tx("MERCH_A", new BigDecimal("99.99"), now),
                ctx(List.of(
                        tx("MERCH_A", new BigDecimal("99.99"), now.minus(2, ChronoUnit.MINUTES)),
                        tx("MERCH_A", new BigDecimal("99.99"), now.minus(4, ChronoUnit.MINUTES)))));
        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void differentAmountsAtDifferentMerchants_passes() {
        Instant now = Instant.now();
        RuleResult result = rule.evaluate(
                tx("ALIEXPRESS", new BigDecimal("150.00"), now),
                ctx(List.of(
                        tx("AMAZON", new BigDecimal("99.99"), now.minus(5, ChronoUnit.MINUTES)),
                        tx("EBAY",   new BigDecimal("49.99"), now.minus(3, ChronoUnit.MINUTES)))));
        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void historyOutsideWindow_passes() {
        Instant now = Instant.now();
        // Both historical transactions are outside the 10-minute window
        RuleResult result = rule.evaluate(
                tx("ALIEXPRESS", new BigDecimal("99.99"), now),
                ctx(List.of(
                        tx("AMAZON", new BigDecimal("99.99"), now.minus(15, ChronoUnit.MINUTES)),
                        tx("EBAY",   new BigDecimal("99.99"), now.minus(12, ChronoUnit.MINUTES)))));
        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void disabledRule_isEnabled_returnsFalse() {
        RuleProperties props = new RuleProperties();
        props.getCardCloning().setEnabled(false);
        assertThat(new CardCloningRule(props).isEnabled()).isFalse();
    }

    @Test
    void sameAmountDifferentCurrencyAtDifferentMerchants_passes() {
        Instant now = Instant.now();
        // Same numeric amount at two other merchants, but neither shares the current
        // transaction's currency — must not be pooled as equivalent magnitude.
        RuleResult result = rule.evaluate(
                tx("ALIEXPRESS", new BigDecimal("99.99"), "ZAR", now),
                ctx(List.of(
                        tx("AMAZON", new BigDecimal("99.99"), "USD", now.minus(5, ChronoUnit.MINUTES)),
                        tx("EBAY",   new BigDecimal("99.99"), "USD", now.minus(3, ChronoUnit.MINUTES)))));
        assertThat(result.isViolation()).isFalse();
    }

    @Test
    void sameAmountMixedCurrencies_onlyMatchingCurrencyCounts() {
        Instant now = Instant.now();
        // One other-currency match (doesn't count) plus one same-currency match: still only
        // 1 same-currency distinct merchant, below the min-different-merchants threshold of 2.
        RuleResult result = rule.evaluate(
                tx("ALIEXPRESS", new BigDecimal("99.99"), "ZAR", now),
                ctx(List.of(
                        tx("AMAZON", new BigDecimal("99.99"), "USD", now.minus(5, ChronoUnit.MINUTES)),
                        tx("EBAY",   new BigDecimal("99.99"), "ZAR", now.minus(3, ChronoUnit.MINUTES)))));
        assertThat(result.isViolation()).isFalse();
    }

    private Transaction tx(String merchantId, BigDecimal amount, Instant ts) {
        return tx(merchantId, amount, "ZAR", ts);
    }

    private Transaction tx(String merchantId, BigDecimal amount, String currency, Instant ts) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId("CUST_001")
                .merchantId(merchantId).amount(amount).currency(currency)
                .timestamp(ts).build();
    }

    private EvaluationContext ctx(List<Transaction> recent) {
        return EvaluationContext.builder()
                .recentCustomerTransactions(recent)
                .build();
    }
}
