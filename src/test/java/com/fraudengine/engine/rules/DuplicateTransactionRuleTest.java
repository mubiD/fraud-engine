package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateTransactionRuleTest {

    private DuplicateTransactionRule rule;

    @BeforeEach
    void setUp() {
        RuleProperties props = new RuleProperties();
        props.getDuplicate().setCardPresentWindowSeconds(30);
        props.getDuplicate().setCardNotPresentWindowSeconds(300);
        rule = new DuplicateTransactionRule(props);
    }

    // ---- CNP (online / MOTO) — 300-second window ----

    @Test
    void noHistory_passes() {
        assertThat(rule.evaluate(tx("M", new BigDecimal("100"), Instant.now()), ctx(List.of())).isViolation()).isFalse();
    }

    @Test
    void cnp_sameMerchantAmountWithinWindow_isViolation() {
        Instant now = Instant.now();
        assertThat(rule.evaluate(
                tx("M", new BigDecimal("100"), now),
                ctx(List.of(tx("M", new BigDecimal("100"), now.minus(1, ChronoUnit.MINUTES))))
        ).isViolation()).isTrue();
    }

    @Test
    void cnp_withinLongWindow_isViolation() {
        Instant now = Instant.now();
        // 4 minutes ago = 240 s, inside the 300 s CNP window
        assertThat(rule.evaluate(
                tx("M", new BigDecimal("100"), now),
                ctx(List.of(tx("M", new BigDecimal("100"), now.minus(4, ChronoUnit.MINUTES))))
        ).isViolation()).isTrue();
    }

    @Test
    void cnp_outsideLongWindow_passes() {
        Instant now = Instant.now();
        // 10 minutes ago = 600 s, outside the 300 s CNP window
        assertThat(rule.evaluate(
                tx("M", new BigDecimal("100"), now),
                ctx(List.of(tx("M", new BigDecimal("100"), now.minus(10, ChronoUnit.MINUTES))))
        ).isViolation()).isFalse();
    }

    @Test
    void differentAmount_passes() {
        Instant now = Instant.now();
        assertThat(rule.evaluate(
                tx("M", new BigDecimal("200"), now),
                ctx(List.of(tx("M", new BigDecimal("100"), now.minus(1, ChronoUnit.MINUTES))))
        ).isViolation()).isFalse();
    }

    // ---- CP / Contactless / ATM — extended 120-second window (Gap 6 fix) ----

    @Test
    void cardPresent_withinExtendedWindow_isViolation() {
        Instant now = Instant.now();
        RuleProperties props = new RuleProperties();
        props.getDuplicate().setCardPresentWindowSeconds(120);
        DuplicateTransactionRule extendedRule = new DuplicateTransactionRule(props);
        // 90 s ago — inside the 120 s window
        assertThat(extendedRule.evaluate(
                tx("M", new BigDecimal("100"), now, TransactionType.CARD_PRESENT),
                ctx(List.of(tx("M", new BigDecimal("100"), now.minus(90, ChronoUnit.SECONDS), TransactionType.CARD_PRESENT)))
        ).isViolation()).isTrue();
    }

    @Test
    void cardPresent_outsideExtendedWindow_passes() {
        Instant now = Instant.now();
        RuleProperties props = new RuleProperties();
        props.getDuplicate().setCardPresentWindowSeconds(120);
        DuplicateTransactionRule extendedRule = new DuplicateTransactionRule(props);
        // 150 s ago — outside the 120 s window
        assertThat(extendedRule.evaluate(
                tx("M", new BigDecimal("100"), now, TransactionType.CARD_PRESENT),
                ctx(List.of(tx("M", new BigDecimal("100"), now.minus(150, ChronoUnit.SECONDS), TransactionType.CARD_PRESENT)))
        ).isViolation()).isFalse();
    }

    @Test
    void contactless_withinWindow_isViolation() {
        Instant now = Instant.now();
        assertThat(rule.evaluate(
                tx("M", new BigDecimal("20"), now, TransactionType.CONTACTLESS),
                ctx(List.of(tx("M", new BigDecimal("20"), now.minus(10, ChronoUnit.SECONDS), TransactionType.CONTACTLESS)))
        ).isViolation()).isTrue();
    }

    @Test
    void atm_withinExtendedWindow_isViolation() {
        Instant now = Instant.now();
        RuleProperties props = new RuleProperties();
        props.getDuplicate().setCardPresentWindowSeconds(120);
        DuplicateTransactionRule extendedRule = new DuplicateTransactionRule(props);
        // 60 s ago — inside the 120 s ATM window (was outside the old 30 s window)
        assertThat(extendedRule.evaluate(
                tx("ATM_A", new BigDecimal("200"), now, TransactionType.ATM),
                ctx(List.of(tx("ATM_A", new BigDecimal("200"), now.minus(60, ChronoUnit.SECONDS), TransactionType.ATM)))
        ).isViolation()).isTrue();
    }

    @Test
    void atm_outsideExtendedWindow_passes() {
        Instant now = Instant.now();
        RuleProperties props = new RuleProperties();
        props.getDuplicate().setCardPresentWindowSeconds(120);
        DuplicateTransactionRule extendedRule = new DuplicateTransactionRule(props);
        // 180 s ago — outside the 120 s ATM window
        assertThat(extendedRule.evaluate(
                tx("ATM_A", new BigDecimal("200"), now, TransactionType.ATM),
                ctx(List.of(tx("ATM_A", new BigDecimal("200"), now.minus(180, ChronoUnit.SECONDS), TransactionType.ATM)))
        ).isViolation()).isFalse();
    }

    // ---- Currency check (Gap 7 fix): duplicate requires matching currency ----

    @Test
    void sameMerchantAmountSameCurrency_isViolation() {
        Instant now = Instant.now();
        Transaction subject = Transaction.builder()
                .id(UUID.randomUUID()).customerId("C").merchantId("M")
                .amount(new BigDecimal("100")).currency("ZAR").timestamp(now).build();
        Transaction history = Transaction.builder()
                .id(UUID.randomUUID()).customerId("C").merchantId("M")
                .amount(new BigDecimal("100")).currency("ZAR")
                .timestamp(now.minus(1, ChronoUnit.MINUTES)).build();
        assertThat(rule.evaluate(subject, ctx(List.of(history))).isViolation()).isTrue();
    }

    @Test
    void sameMerchantAmountDifferentCurrency_passes() {
        Instant now = Instant.now();
        Transaction subject = Transaction.builder()
                .id(UUID.randomUUID()).customerId("C").merchantId("M")
                .amount(new BigDecimal("100")).currency("USD").timestamp(now).build();
        Transaction history = Transaction.builder()
                .id(UUID.randomUUID()).customerId("C").merchantId("M")
                .amount(new BigDecimal("100")).currency("ZAR")
                .timestamp(now.minus(1, ChronoUnit.MINUTES)).build();
        assertThat(rule.evaluate(subject, ctx(List.of(history))).isViolation()).isFalse();
    }

    // ---- Helpers ----

    private Transaction tx(String merchantId, BigDecimal amount, Instant ts) {
        return tx(merchantId, amount, ts, TransactionType.CARD_NOT_PRESENT);
    }

    private Transaction tx(String merchantId, BigDecimal amount, Instant ts, TransactionType type) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .customerId("C")
                .merchantId(merchantId)
                .amount(amount)
                .currency("GBP")
                .timestamp(ts)
                .transactionType(type)
                .build();
    }

    private EvaluationContext ctx(List<Transaction> recent) {
        return EvaluationContext.builder()
                .recentCustomerTransactions(recent)
                .build();
    }
}
