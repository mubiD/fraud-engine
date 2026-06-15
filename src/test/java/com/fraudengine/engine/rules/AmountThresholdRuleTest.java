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
        props.getAmountThreshold().setEnabled(true);
        rule = new AmountThresholdRule(props);
        emptyContext = EvaluationContext.builder()
                .recentCustomerTransactions(List.of())
                .blacklistedMerchantIds(Set.of())
                .build();
    }

    @Test
    void belowThreshold_passes() {
        assertThat(rule.evaluate(transaction(new BigDecimal("4999.99")), emptyContext).isViolation()).isFalse();
    }

    @Test
    void exactlyAtThreshold_passes() {
        assertThat(rule.evaluate(transaction(new BigDecimal("5000.00")), emptyContext).isViolation()).isFalse();
    }

    @Test
    void aboveThreshold_isViolation() {
        RuleResult result = rule.evaluate(transaction(new BigDecimal("5000.01")), emptyContext);
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getRuleName()).isEqualTo("AMOUNT_THRESHOLD");
    }

    @Test
    void disabledRule_isEnabled_returnsFalse() {
        RuleProperties props = new RuleProperties();
        props.getAmountThreshold().setEnabled(false);
        assertThat(new AmountThresholdRule(props).isEnabled()).isFalse();
    }

    private Transaction transaction(BigDecimal amount) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId("CUST_001").merchantId("MERCH_001")
                .amount(amount).currency("GBP").timestamp(Instant.now()).build();
    }
}
