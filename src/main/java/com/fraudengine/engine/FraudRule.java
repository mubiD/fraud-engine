package com.fraudengine.engine;

import com.fraudengine.model.Transaction;

import java.util.Map;

public interface FraudRule {

    /**
     * Evaluating a rule against a shape it doesn't expect (an unanticipated null, an edge
     * case in {@code context}) should return {@link RuleResult#pass}, not throw — but if a
     * rule does throw an unchecked exception, {@link RuleEngine} catches it and treats that
     * rule as a pass rather than aborting the other rules' evaluation, so a single-rule bug
     * degrades to "one rule skipped" instead of failing the whole assessment.
     */
    RuleResult evaluate(Transaction transaction, EvaluationContext context);

    String getRuleName();

    String getRuleVersion();

    int getPriority();

    boolean isEnabled();

    /** Live tunable parameters for this rule, as configured in the running instance. */
    Map<String, Object> getConfig();
}
