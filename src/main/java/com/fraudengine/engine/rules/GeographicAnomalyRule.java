package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@Order(5)
public class GeographicAnomalyRule implements FraudRule {

    private static final String RULE_NAME = "GEOGRAPHIC_ANOMALY";
    private static final String RULE_VERSION = "1.0";

    private static final double EARTH_RADIUS_KM = 6371.0;
    // Minimum time gap required to perform a speed check.
    // Transactions < 1 minute apart are ignored — clock skew and batched
    // submissions can produce identical or near-identical timestamps.
    private static final double MIN_TIME_DIFF_HOURS = 1.0 / 60.0;

    private final RuleProperties properties;

    public GeographicAnomalyRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, EvaluationContext context) {
        if (transaction.getLatitude() == null || transaction.getLongitude() == null) {
            return RuleResult.pass(RULE_NAME);
        }

        int windowMinutes = properties.getGeographic().getWindowMinutes();
        double maxSpeedKmh = properties.getGeographic().getMaxTravelSpeedKmh();
        Instant windowStart = transaction.getTimestamp().minus(windowMinutes, ChronoUnit.MINUTES);

        return context.getRecentCustomerTransactions().stream()
                .filter(t -> t.getLatitude() != null && t.getLongitude() != null)
                .filter(t -> t.getTimestamp().isAfter(windowStart))
                .filter(t -> isPhysicallyImpossible(transaction, t, maxSpeedKmh))
                .findFirst()
                .map(conflicting -> RuleResult.violation(RULE_NAME, RULE_VERSION,
                        String.format("Physically impossible travel: [%.4f,%.4f] and [%.4f,%.4f] within %d minutes",
                                transaction.getLatitude(), transaction.getLongitude(),
                                conflicting.getLatitude(), conflicting.getLongitude(), windowMinutes),
                        Severity.CRITICAL))
                .orElseGet(() -> RuleResult.pass(RULE_NAME));
    }

    private boolean isPhysicallyImpossible(Transaction a, Transaction b, double maxSpeedKmh) {
        double timeDiffHours = Math.abs(
                Duration.between(a.getTimestamp(), b.getTimestamp()).toMinutes()) / 60.0;
        if (timeDiffHours < MIN_TIME_DIFF_HOURS) {
            return false;
        }
        double distanceKm = haversineDistanceKm(
                a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
        return (distanceKm / timeDiffHours) > maxSpeedKmh;
    }

    private double haversineDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    @Override public String getRuleName()    { return RULE_NAME; }
    @Override public String getRuleVersion() { return RULE_VERSION; }
    @Override public int getPriority()       { return 5; }
    @Override public boolean isEnabled()     { return properties.getGeographic().isEnabled(); }
}
