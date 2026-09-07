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

class DeviceFingerprintRuleTest {

    private DeviceFingerprintRule rule;

    @BeforeEach
    void setUp() {
        RuleProperties props = new RuleProperties();
        props.getDeviceFingerprint().setEnabled(true);
        props.getDeviceFingerprint().setWindowMinutes(60);
        rule = new DeviceFingerprintRule(props);
    }

    @Test
    void noFingerprintOnCurrentTransaction_passes() {
        Transaction current = tx(null, Instant.now());
        Transaction prior   = tx("device-A", Instant.now().minus(10, ChronoUnit.MINUTES));
        assertThat(rule.evaluate(current, ctx(List.of(prior))).isViolation()).isFalse();
    }

    @Test
    void noPriorFingerprintedTransactions_passes() {
        // First-ever fingerprinted transaction — no baseline to compare against
        Transaction current = tx("device-A", Instant.now());
        Transaction prior   = tx(null, Instant.now().minus(10, ChronoUnit.MINUTES));
        assertThat(rule.evaluate(current, ctx(List.of(prior))).isViolation()).isFalse();
    }

    @Test
    void emptyHistory_passes() {
        assertThat(rule.evaluate(tx("device-A", Instant.now()), ctx(List.of())).isViolation()).isFalse();
    }

    @Test
    void knownDevice_passes() {
        Instant now = Instant.now();
        Transaction current = tx("device-A", now);
        Transaction prior   = tx("device-A", now.minus(15, ChronoUnit.MINUTES));
        assertThat(rule.evaluate(current, ctx(List.of(prior))).isViolation()).isFalse();
    }

    @Test
    void unknownDevice_withKnownHistory_isViolation() {
        Instant now = Instant.now();
        Transaction current = tx("device-NEW", now);
        Transaction prior   = tx("device-A", now.minus(15, ChronoUnit.MINUTES));

        RuleResult result = rule.evaluate(current, ctx(List.of(prior)));
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getRuleName()).isEqualTo("DEVICE_FINGERPRINT");
        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void multipleKnownDevices_currentIsNew_isViolation() {
        Instant now = Instant.now();
        Transaction current = tx("device-ATTACKER", now);
        List<Transaction> history = List.of(
                tx("device-A", now.minus(10, ChronoUnit.MINUTES)),
                tx("device-B", now.minus(20, ChronoUnit.MINUTES)));

        assertThat(rule.evaluate(current, ctx(history)).isViolation()).isTrue();
    }

    @Test
    void priorFingerprintOutsideWindow_treatedAsNoHistory_passes() {
        Instant now = Instant.now();
        Transaction current = tx("device-NEW", now);
        // Prior fingerprinted transaction is outside the 60-minute window
        Transaction prior = tx("device-A", now.minus(90, ChronoUnit.MINUTES));
        assertThat(rule.evaluate(current, ctx(List.of(prior))).isViolation()).isFalse();
    }

    @Test
    void blankFingerprint_passes() {
        Transaction current = tx("   ", Instant.now());
        Transaction prior   = tx("device-A", Instant.now().minus(10, ChronoUnit.MINUTES));
        assertThat(rule.evaluate(current, ctx(List.of(prior))).isViolation()).isFalse();
    }

    @Test
    void disabledRule_isEnabled_returnsFalse() {
        RuleProperties props = new RuleProperties();
        props.getDeviceFingerprint().setEnabled(false);
        assertThat(new DeviceFingerprintRule(props).isEnabled()).isFalse();
    }

    private Transaction tx(String fingerprint, Instant ts) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId("CUST_001").merchantId("MERCH_001")
                .amount(new BigDecimal("250.00")).currency("ZAR")
                .deviceFingerprint(fingerprint).timestamp(ts).build();
    }

    private EvaluationContext ctx(List<Transaction> recent) {
        return EvaluationContext.builder()
                .recentCustomerTransactions(recent)
                .build();
    }
}
