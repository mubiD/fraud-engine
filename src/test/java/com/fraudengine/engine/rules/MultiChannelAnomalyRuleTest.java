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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MultiChannelAnomalyRuleTest {

    private MultiChannelAnomalyRule rule;

    @BeforeEach
    void setUp() {
        RuleProperties props = new RuleProperties();
        props.getMultiChannel().setEnabled(true);
        props.getMultiChannel().setWindowMinutes(5);
        rule = new MultiChannelAnomalyRule(props);
    }

    @Test
    void noHistory_passes() {
        assertThat(rule.evaluate(tx(TransactionType.CARD_NOT_PRESENT, Instant.now()), ctx(List.of())).isViolation()).isFalse();
    }

    @Test
    void cnp_followedByPhysical_withinWindow_isViolation() {
        Instant now = Instant.now();
        RuleResult result = rule.evaluate(
                tx(TransactionType.CARD_PRESENT, now),
                ctx(List.of(tx(TransactionType.CARD_NOT_PRESENT, now.minus(2, ChronoUnit.MINUTES)))));
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getRuleName()).isEqualTo("MULTI_CHANNEL_ANOMALY");
        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void physical_followedByCnp_withinWindow_isViolation() {
        Instant now = Instant.now();
        assertThat(rule.evaluate(
                tx(TransactionType.CARD_NOT_PRESENT, now),
                ctx(List.of(tx(TransactionType.CARD_PRESENT, now.minus(3, ChronoUnit.MINUTES)))))
                .isViolation()).isTrue();
    }

    @Test
    void contactless_followedByCnp_withinWindow_isViolation() {
        Instant now = Instant.now();
        assertThat(rule.evaluate(
                tx(TransactionType.CARD_NOT_PRESENT, now),
                ctx(List.of(tx(TransactionType.CONTACTLESS, now.minus(1, ChronoUnit.MINUTES)))))
                .isViolation()).isTrue();
    }

    @Test
    void atm_followedByCnp_withinWindow_isViolation() {
        Instant now = Instant.now();
        assertThat(rule.evaluate(
                tx(TransactionType.CARD_NOT_PRESENT, now),
                ctx(List.of(tx(TransactionType.ATM, now.minus(4, ChronoUnit.MINUTES)))))
                .isViolation()).isTrue();
    }

    @Test
    void sameChannel_physical_passes() {
        Instant now = Instant.now();
        assertThat(rule.evaluate(
                tx(TransactionType.CARD_PRESENT, now),
                ctx(List.of(tx(TransactionType.CONTACTLESS, now.minus(2, ChronoUnit.MINUTES)))))
                .isViolation()).isFalse();
    }

    @Test
    void sameChannel_cnp_passes() {
        Instant now = Instant.now();
        assertThat(rule.evaluate(
                tx(TransactionType.CARD_NOT_PRESENT, now),
                ctx(List.of(tx(TransactionType.CARD_NOT_PRESENT, now.minus(2, ChronoUnit.MINUTES)))))
                .isViolation()).isFalse();
    }

    @Test
    void channelSwitch_outsideWindow_passes() {
        Instant now = Instant.now();
        // Prior physical transaction is 10 minutes ago, outside the 5-minute window
        assertThat(rule.evaluate(
                tx(TransactionType.CARD_NOT_PRESENT, now),
                ctx(List.of(tx(TransactionType.CARD_PRESENT, now.minus(10, ChronoUnit.MINUTES)))))
                .isViolation()).isFalse();
    }

    @Test
    void disabledRule_isEnabled_returnsFalse() {
        RuleProperties props = new RuleProperties();
        props.getMultiChannel().setEnabled(false);
        assertThat(new MultiChannelAnomalyRule(props).isEnabled()).isFalse();
    }

    private Transaction tx(TransactionType type, Instant ts) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId("CUST_001").merchantId("MERCH_001")
                .amount(new BigDecimal("250.00")).currency("ZAR")
                .transactionType(type).timestamp(ts).build();
    }

    private EvaluationContext ctx(List<Transaction> recent) {
        return EvaluationContext.builder()
                .recentCustomerTransactions(recent)
                .build();
    }
}
