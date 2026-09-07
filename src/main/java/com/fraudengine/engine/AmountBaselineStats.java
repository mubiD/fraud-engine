package com.fraudengine.engine;

import com.fraudengine.model.Transaction;
import com.fraudengine.streams.DailyAmountStats;

import java.util.List;

// Precomputed personal-spending baseline for CustomerAmountAnomalyRule — count, population
// mean, and population standard deviation of a customer's amounts over their configured
// lookback window. Replaces passing the rule a raw List<Transaction> and letting it do the
// mean/stdDev math itself: EvaluationContextBuilder now has two source paths for this data
// (a Kafka Streams state store that only ever holds aggregates, never raw transactions; and
// a Postgres fallback that does have the raw list), and the two factories below guarantee
// both paths produce this type via a shared, single definition of "what a baseline is."
public record AmountBaselineStats(long count, double mean, double stdDev) {

    private static final AmountBaselineStats EMPTY = new AmountBaselineStats(0, 0.0, 0.0);

    public static AmountBaselineStats empty() {
        return EMPTY;
    }

    // Postgres fallback path: raw transaction list is available, so use the exact
    // definitional formula (mean, then average of squared deviations from that mean) —
    // numerically the most direct, and preserves this rule's pre-existing behaviour exactly.
    public static AmountBaselineStats from(List<Transaction> history) {
        if (history.isEmpty()) {
            return EMPTY;
        }
        double mean = history.stream().mapToDouble(t -> t.getAmount().doubleValue()).average().orElse(0);
        double variance = history.stream()
                .mapToDouble(t -> Math.pow(t.getAmount().doubleValue() - mean, 2))
                .average().orElse(0);
        return new AmountBaselineStats(history.size(), mean, Math.sqrt(variance));
    }

    // Kafka Streams path: only (count, sum, sumOfSquares) aggregates are available — raw
    // per-transaction amounts are never retained in the state store (see DailyAmountStats).
    // Mean/variance are derived via the sum-of-squares identity (Var = E[X^2] - E[X]^2),
    // the only option without raw values. That identity is less numerically stable than the
    // definitional formula above (catastrophic cancellation for a large mean with small
    // variance can leave a tiny negative value where the true variance is ~0), so the result
    // is clamped at zero and snapped to exactly 0 below a small epsilon — this is a heuristic
    // fraud signal, not a financial calculation, so this approximation is an acceptable
    // trade for not having to retain 90 days of raw transactions per customer.
    public static AmountBaselineStats of(DailyAmountStats aggregate) {
        if (aggregate.count() <= 0) {
            return EMPTY;
        }
        double mean = aggregate.sum() / aggregate.count();
        double variance = Math.max(0.0, (aggregate.sumOfSquares() / aggregate.count()) - (mean * mean));
        double stdDev = Math.sqrt(variance);
        if (stdDev < 1e-9) {
            stdDev = 0.0;
        }
        return new AmountBaselineStats(aggregate.count(), mean, stdDev);
    }
}
