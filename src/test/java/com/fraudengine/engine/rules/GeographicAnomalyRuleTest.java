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

    private Transaction tx(double lat, double lon, Instant ts) {
        return Transaction.builder().id(UUID.randomUUID()).customerId("C").merchantId("M")
                .amount(BigDecimal.TEN).currency("GBP").latitude(lat).longitude(lon).timestamp(ts).build();
    }

    private EvaluationContext ctx(List<Transaction> recent) {
        return EvaluationContext.builder().recentCustomerTransactions(recent).blacklistedMerchantIds(Set.of()).build();
    }
}
