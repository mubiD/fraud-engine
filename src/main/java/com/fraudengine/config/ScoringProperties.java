package com.fraudengine.config;

import com.fraudengine.model.enums.Severity;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

// Fraud-verdict scoring, replacing the older flat additive point model
// (LOW=10/MEDIUM=25/HIGH=50/CRITICAL=100, capped at 100, fraudulent >= 50).
//
// That model conflated two different things: "one strong signal fired" and
// "several weak, possibly-correlated signals happened to coincide" produced an
// identical verdict once the point total crossed 50 — see DESIGN.md §5 and §11.
//
// This replaces it with a log-odds (naive-Bayes) combination: each rule carries
// a likelihood ratio — how much more likely fraud is, given that rule fired,
// versus not — keyed by "RULE_NAME:SEVERITY" so rules whose severity varies at
// runtime (e.g. VelocityRule's high-risk-category escalation) are calibrated
// per variant, not just per rule. Posterior probability of fraud is the sigmoid
// of (prior log-odds + sum of each fired rule's log-likelihood-ratio).
//
// The likelihood ratios below are domain-judgment starting points, not values
// derived from labelled outcome data — this system has no confirmed-fraud /
// false-positive feedback loop yet to calibrate against. They should be revised
// once one exists, rather than treated as authoritative.
@ConfigurationProperties(prefix = "fraud.scoring")
public class ScoringProperties {

    // Assumed base rate of fraud across all transactions, before any rule evidence.
    private double priorFraudProbability = 0.01;

    // Posterior probability at or above which a transaction is marked FLAGGED.
    private double fraudProbabilityThreshold = 0.5;

    // Posterior probability at or above which a transaction is marked PENDING_REVIEW
    // instead of CLEARED (but below fraudProbabilityThreshold, which takes precedence).
    // Default 0.10 is chosen from the effectiveness suite's own measured behavior: a
    // single weak signal alone scores ~0.02-0.06, two corroborating weak signals
    // combined score ~0.15 — this sits between them, so isolated weak signals stay
    // CLEARED but corroborated combinations move to PENDING_REVIEW instead of being
    // silently treated the same as a clean transaction.
    private double reviewProbabilityThreshold = 0.10;

    // Fallback likelihood ratios used when a fired (ruleName:severity) combination
    // has no specific entry below — keeps scoring functional (rather than throwing)
    // if a new rule or severity variant ships without an explicit calibration, while
    // still logging a warning so the gap gets noticed and closed.
    private double defaultLikelihoodRatioLow = 1.3;
    private double defaultLikelihoodRatioMedium = 3.0;
    private double defaultLikelihoodRatioHigh = 9.0;
    private double defaultLikelihoodRatioCritical = 120.0;

    private Map<String, Double> likelihoodRatios = defaultLikelihoodRatios();

    private static Map<String, Double> defaultLikelihoodRatios() {
        Map<String, Double> m = new HashMap<>();
        // Weak alone: a large amount, by itself, is common among legitimate
        // transactions (flights, electronics, once-off large purchases) — this
        // was the single biggest source of standalone false-positive risk under
        // the old additive model (AmountThresholdRule contributed the largest
        // share of all flags in the sample fraud-summary in README.md).
        m.put("AMOUNT_THRESHOLD:HIGH", 2.0);

        // Rapid repeated transactions are a strong raw signal (card testing,
        // stolen-card burn-through) even without a high-risk category boost.
        m.put("VELOCITY:HIGH", 130.0);
        m.put("VELOCITY:CRITICAL", 300.0);

        m.put("DUPLICATE_TRANSACTION:CRITICAL", 150.0);
        m.put("BLACKLISTED_MERCHANT:CRITICAL", 800.0);
        m.put("GEOGRAPHIC_ANOMALY:CRITICAL", 400.0);

        // Weak alone by design — see CardCloningRule/TimeOfDayAnomalyRule javadoc
        // for why these were always meant to need a second corroborating signal.
        m.put("CARD_CLONING:MEDIUM", 6.0);
        m.put("TIME_OF_DAY_ANOMALY:MEDIUM", 3.0);

        m.put("HIGH_RISK_MERCHANT_CATEGORY:HIGH", 130.0);
        m.put("HIGH_RISK_MERCHANT_CATEGORY:MEDIUM", 5.0);

        m.put("DEVICE_FINGERPRINT:HIGH", 160.0);
        m.put("MULTI_CHANNEL_ANOMALY:MEDIUM", 5.0);

        // Deliberately modest: CrossMerchantVelocityRule and VelocityRule share
        // the same default 10-minute window, so any transaction set meeting this
        // rule's >=10 threshold has almost certainly already tripped VELOCITY —
        // a high ratio here would double-count what is substantively one pattern.
        m.put("CROSS_MERCHANT_VELOCITY:MEDIUM", 3.0);

        m.put("CUMULATIVE_SPENDING:HIGH", 140.0);

        // Weak alone, like AMOUNT_THRESHOLD, but a more specific signal — this fires on
        // deviation from the customer's OWN history, not a global size cutoff, so it's
        // valued slightly above the generic weak-alone cluster. Still a domain-judgment
        // starting point: no confirmed-fraud/false-positive data exists yet to calibrate
        // against (see the outcome-recording endpoint this is meant to eventually use).
        m.put("CUSTOMER_AMOUNT_ANOMALY:MEDIUM", 5.0);
        return m;
    }

    public double getPriorFraudProbability() { return priorFraudProbability; }
    public void setPriorFraudProbability(double v) { this.priorFraudProbability = v; }
    public double getFraudProbabilityThreshold() { return fraudProbabilityThreshold; }
    public void setFraudProbabilityThreshold(double v) { this.fraudProbabilityThreshold = v; }
    public double getReviewProbabilityThreshold() { return reviewProbabilityThreshold; }
    public void setReviewProbabilityThreshold(double v) { this.reviewProbabilityThreshold = v; }

    @PostConstruct
    public void validate() {
        if (reviewProbabilityThreshold >= fraudProbabilityThreshold) {
            throw new IllegalStateException(String.format(
                    "fraud.scoring.review-probability-threshold (%s) must be less than "
                    + "fraud.scoring.fraud-probability-threshold (%s)",
                    reviewProbabilityThreshold, fraudProbabilityThreshold));
        }
    }
    public double getDefaultLikelihoodRatioLow() { return defaultLikelihoodRatioLow; }
    public void setDefaultLikelihoodRatioLow(double v) { this.defaultLikelihoodRatioLow = v; }
    public double getDefaultLikelihoodRatioMedium() { return defaultLikelihoodRatioMedium; }
    public void setDefaultLikelihoodRatioMedium(double v) { this.defaultLikelihoodRatioMedium = v; }
    public double getDefaultLikelihoodRatioHigh() { return defaultLikelihoodRatioHigh; }
    public void setDefaultLikelihoodRatioHigh(double v) { this.defaultLikelihoodRatioHigh = v; }
    public double getDefaultLikelihoodRatioCritical() { return defaultLikelihoodRatioCritical; }
    public void setDefaultLikelihoodRatioCritical(double v) { this.defaultLikelihoodRatioCritical = v; }
    public Map<String, Double> getLikelihoodRatios() { return likelihoodRatios; }
    public void setLikelihoodRatios(Map<String, Double> v) { this.likelihoodRatios = v; }

    public double defaultLikelihoodRatioFor(Severity severity) {
        return switch (severity) {
            case LOW -> defaultLikelihoodRatioLow;
            case MEDIUM -> defaultLikelihoodRatioMedium;
            case HIGH -> defaultLikelihoodRatioHigh;
            case CRITICAL -> defaultLikelihoodRatioCritical;
        };
    }
}
