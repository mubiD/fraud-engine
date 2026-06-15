package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityRuleTest {

    private VelocityRule rule;

    @BeforeEach
    void setUp() {
        RuleProperties props = new RuleProperties();
        props.getVelocity().setMaxTransactions(5);
        props.getVelocity().setWindowMinutes(10);
        rule = new VelocityRule(props);
    }

    @Test
    void underLimit_passes() {
        Transaction current = transaction(Instant.now());
        assertThat(rule.evaluate(current, context(prior(4, current.getTimestamp(), current.getCustomerId()))).isViolation()).isFalse();
    }

    @Test
    void atLimit_isViolation() {
        Transaction current = transaction(Instant.now());
        RuleResult result = rule.evaluate(current, context(prior(5, current.getTimestamp(), current.getCustomerId())));
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getRuleName()).isEqualTo("VELOCITY");
    }

    @Test
    void transactionsOutsideWindow_ignored() {
        Transaction current = transaction(Instant.now());
        List<Transaction> old = prior(10, current.getTimestamp().minus(20, ChronoUnit.MINUTES), current.getCustomerId());
        assertThat(rule.evaluate(current, context(old)).isViolation()).isFalse();
    }

    private List<Transaction> prior(int count, Instant base, String customerId) {
        return IntStream.range(0, count).mapToObj(i ->
                Transaction.builder().id(UUID.randomUUID()).customerId(customerId)
                        .merchantId("M").amount(BigDecimal.TEN).currency("GBP")
                        .timestamp(base.minus(i + 1, ChronoUnit.MINUTES)).build()
        ).toList();
    }

    private Transaction transaction(Instant ts) {
        return Transaction.builder().id(UUID.randomUUID()).customerId("CUST_001")
                .merchantId("M").amount(BigDecimal.TEN).currency("GBP").timestamp(ts).build();
    }

    private EvaluationContext context(List<Transaction> recent) {
        return EvaluationContext.builder().recentCustomerTransactions(recent).blacklistedMerchantIds(Set.of()).build();
    }
}
