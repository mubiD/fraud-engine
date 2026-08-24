package com.fraudengine.engine;

import com.fraudengine.model.Transaction;

public interface FraudRule {

    RuleResult evaluate(Transaction transaction, EvaluationContext context);

    String getRuleName();

    String getRuleVersion();

    int getPriority();

    boolean isEnabled();
}
