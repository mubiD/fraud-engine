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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GeographicAnomalyRuleTest {

    private GeographicAnomalyRule rule;

    @BeforeEach
    void setUp() {
        RuleProperties props = new RuleProperties();
        props.getGeographic().setWindowMinutes(60);
        rule = new GeographicAnomalyRule(props);
    }

    @Test
    void noCoordinates_passes() {
        Transaction t = Transaction.builder().id(UUID.randomUUID()).customerId("C").merchantId("M")
                .amount(BigDecimal.TEN).currency("GBP").timestamp(Instant.now()).build();
        assertThat(rule.evaluate(t, ctx(List.of())).isViolation()).isFalse();
    }

    @Test
    void sameLocation_passes() {
        Instant now = Instant.now();
        assertThat(rule.evaluate(
                tx(51.5074, -0.1278, now),
                ctx(List.of(tx(51.5080, -0.1270, now.minus(30, ChronoUnit.MINUTES))))
        ).isViolation()).isFalse();
    }

    @Test
    void londonToNewYorkIn30Minutes_isViolation() {
        Instant now = Instant.now();
        var result = rule.evaluate(
                tx(40.7128, -74.0060, now),
                ctx(List.of(tx(51.5074, -0.1278, now.minus(30, ChronoUnit.MINUTES))))
        );
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getRuleName()).isEqualTo("GEOGRAPHIC_ANOMALY");
    }

    @Test
    void transactionsLessThanOneMinuteApart_skipSpeedCheck_passes() {
        Instant now = Instant.now();
        // London → New York in 30 seconds is physically impossible,
        // but the < 1-minute guard skips the speed check to prevent false positives
        // caused by clock skew or near-simultaneous auth attempts.
        assertThat(rule.evaluate(
                tx(40.7128, -74.0060, now),
                ctx(List.of(tx(51.5074, -0.1278, now.minus(30, ChronoUnit.SECONDS))))
        ).isViolation()).isFalse();
    }

    // ---- Merchant location fallback (Gap 8) ----

    @Test
    void noCoordinates_merchantLocationInContext_usedAsFallback_isViolation() {
        Instant now = Instant.now();
        // Transaction has no coordinates. Builder would have resolved merchant location
        Transaction current = Transaction.builder().id(UUID.randomUUID()).customerId("C").merchantId("MERCH_LON")
                .amount(BigDecimal.TEN).currency("GBP").timestamp(now).build();
        // Prior transaction has explicit coordinates (Cape Town)
        Transaction prior = tx(-33.9249, 18.4241, now.minus(30, ChronoUnit.MINUTES));

        // Context carries the merchant's resolved location (London)
        EvaluationContext ctx = EvaluationContext.builder()
                .recentCustomerTransactions(List.of(prior))
                .merchantLatitude(51.5074)
                .merchantLongitude(-0.1278)
                .build();

        assertThat(rule.evaluate(current, ctx).isViolation()).isTrue();
    }

    @Test
    void noCoordinates_noMerchantLocationInContext_passes() {
        Instant now = Instant.now();
        Transaction current = Transaction.builder().id(UUID.randomUUID()).customerId("C").merchantId("UNKNOWN")
                .amount(BigDecimal.TEN).currency("GBP").timestamp(now).build();
        Transaction prior = tx(-33.9249, 18.4241, now.minus(30, ChronoUnit.MINUTES));

        // No merchant location resolved: context carries nulls (CARD_NOT_PRESENT case)
        EvaluationContext ctx = EvaluationContext.builder()
                .recentCustomerTransactions(List.of(prior))
                .build();

        assertThat(rule.evaluate(current, ctx).isViolation()).isFalse();
    }

    @Test
    void transactionCoordinates_takePriorityOverMerchantLocation() {
        Instant now = Instant.now();
        // Transaction explicitly has NYC coordinates
        Transaction current = tx(40.7128, -74.0060, now);
        Transaction prior = tx(51.5074, -0.1278, now.minus(30, ChronoUnit.MINUTES));

        // Context also has merchant location (London), but transaction coords must win
        EvaluationContext ctx = EvaluationContext.builder()
                .recentCustomerTransactions(List.of(prior))
                .merchantLatitude(51.5074)
                .merchantLongitude(-0.1278)
                .build();

        // NYC → London in 30 min is impossible regardless of which coords source wins
        assertThat(rule.evaluate(current, ctx).isViolation()).isTrue();
    }

    private Transaction tx(double lat, double lon, Instant ts) {
        return Transaction.builder().id(UUID.randomUUID()).customerId("C").merchantId("M")
                .amount(BigDecimal.TEN).currency("GBP").latitude(lat).longitude(lon).timestamp(ts).build();
    }

    private EvaluationContext ctx(List<Transaction> recent) {
        return EvaluationContext.builder().recentCustomerTransactions(recent).build();
    }
}
