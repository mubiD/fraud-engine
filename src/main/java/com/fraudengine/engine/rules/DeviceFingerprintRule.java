package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detects account takeover by flagging transactions from a device fingerprint
 * that has never been seen for this customer within the lookback window.
 *
 * The rule is skipped when the current transaction has no device fingerprint,
 * keeping it backwards-compatible with producers that do not yet send the field.
 * It is also skipped when the customer has no prior fingerprinted transactions —
 * a first-ever transaction cannot be an anomaly.
 */
@Component
@Order(9)
public class DeviceFingerprintRule implements FraudRule {

    private static final String RULE_NAME = "DEVICE_FINGERPRINT";
    private static final String RULE_VERSION = "1.0";

    private final RuleProperties properties;

    public DeviceFingerprintRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, EvaluationContext context) {
        String fingerprint = transaction.getDeviceFingerprint();
        if (fingerprint == null || fingerprint.isBlank()) {
            return RuleResult.pass(RULE_NAME);
        }

        RuleProperties.DeviceFingerprintConfig config = properties.getDeviceFingerprint();
        Instant windowStart = transaction.getTimestamp()
                .minus(config.getWindowMinutes(), ChronoUnit.MINUTES);

        Set<String> knownFingerprints = context.getRecentCustomerTransactions().stream()
                .filter(t -> t.getTimestamp().isAfter(windowStart))
                .filter(t -> t.getDeviceFingerprint() != null && !t.getDeviceFingerprint().isBlank())
                .map(Transaction::getDeviceFingerprint)
                .collect(Collectors.toSet());

        // No prior fingerprinted transactions — can't determine anomaly yet
        if (knownFingerprints.isEmpty()) {
            return RuleResult.pass(RULE_NAME);
        }

        if (!knownFingerprints.contains(fingerprint)) {
            return RuleResult.violation(RULE_NAME, RULE_VERSION,
                    String.format(
                            "Transaction from unrecognised device fingerprint '%s' — %d known device(s) seen in last %d minutes",
                            fingerprint, knownFingerprints.size(), config.getWindowMinutes()),
                    Severity.HIGH);
        }
        return RuleResult.pass(RULE_NAME);
    }

    @Override public String getRuleName()    { return RULE_NAME; }
    @Override public String getRuleVersion() { return RULE_VERSION; }
    @Override public int getPriority()       { return 9; }
    @Override public boolean isEnabled()     { return properties.getDeviceFingerprint().isEnabled(); }

}
