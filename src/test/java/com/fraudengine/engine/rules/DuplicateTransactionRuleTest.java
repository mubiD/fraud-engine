package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateTransactionRuleTest {

    private DuplicateTransactionRule rule;

    @BeforeEach
    void setUp() {
        RuleProperties props = new RuleProperties();
        props.getDuplicate().setWindowSeconds(300);
        rule = new DuplicateTransactionRule(props);
    }

    @Test
    void noHistory_passes() {
        assertThat(rule.evaluate(tx("M", new BigDecimal("100"), Instant.now()), ctx(List.of())).isViolation()).isFalse();
    }

    @Test
    void sameMerchantAmountWithinWindow_isViolation() {
        Instant now = Instant.now();
        assertThat(rule.evaluate(
                tx("M", new BigDecimal("100"), now),
                ctx(List.of(tx("M", new BigDecimal("100"), now.minus(1, ChronoUnit.MINUTES))))
        ).isViolation()).isTrue();
    }

    @Test
    void differentAmount_passes() {
        Instant now = Instant.now();
        assertThat(rule.evaluate(
                tx("M", new BigDecimal("200"), now),
                ctx(List.of(tx("M", new BigDecimal("100"), now.minus(1, ChronoUnit.MINUTES))))
        ).isViolation()).isFalse();
    }

    @Test
    void outsideWindow_passes() {
        Instant now = Instant.now();
        assertThat(rule.evaluate(
                tx("M", new BigDecimal("100"), now),
                ctx(List.of(tx("M", new BigDecimal("100"), now.minus(10, ChronoUnit.MINUTES))))
        ).isViolation()).isFalse();
    }

    private Transaction tx(String merchantId, BigDecimal amount, Instant ts) {
        return Transaction.builder().id(UUID.randomUUID()).customerId("C").merchantId(merchantId)
                .amount(amount).currency("GBP").timestamp(ts).build();
    }

    private EvaluationContext ctx(List<Transaction> recent) {
        return EvaluationContext.builder().recentCustomerTransactions(recent).blacklistedMerchantIds(Set.of()).build();
    }
}
