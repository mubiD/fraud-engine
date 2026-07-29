package com.fraudengine.engine;

import com.fraudengine.model.Transaction;

import java.util.Map;

public interface FraudRule {

    RuleResult evaluate(Transaction transaction, EvaluationContext context);

    String getRuleName();

    String getRuleVersion();

    int getPriority();

    boolean isEnabled();

    default Map<String, Object> getConfig() {
        return Map.of();
    }
}
