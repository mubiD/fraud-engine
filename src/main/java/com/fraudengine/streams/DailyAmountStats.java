package com.fraudengine.streams;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

// One day's worth of transaction-amount statistics for a single customer, stored per
// epoch-day bucket in CustomerActivityState.dailyAmountBuckets — the running-aggregate
// building block CustomerAmountAnomalyRule's 90-day baseline is computed from. Doubles,
// not BigDecimal: matches the rule's existing double-based z-score math, and this is a
// heuristic fraud signal, not a financial calculation, so BigDecimal's precision guarantees
// buy nothing here.
//
// Also doubles as the raw, pre-mean/stdDev aggregate returned by
// CustomerActivityState.baselineAggregate() — summing several days' (count, sum,
// sumOfSquares) triples together is exactly the same operation as folding one more
// transaction in, so one type serves both the per-bucket and the aggregated-across-buckets
// shape. AmountBaselineStats.of(...) is what turns this into mean/stdDev.
public record DailyAmountStats(long count, double sum, double sumOfSquares) {

    @JsonCreator
    public DailyAmountStats(
            @JsonProperty("count") long count,
            @JsonProperty("sum") double sum,
            @JsonProperty("sumOfSquares") double sumOfSquares) {
        this.count = count;
        this.sum = sum;
        this.sumOfSquares = sumOfSquares;
    }

    public static DailyAmountStats empty() {
        return new DailyAmountStats(0, 0.0, 0.0);
    }

    public DailyAmountStats plus(double amount) {
        return new DailyAmountStats(count + 1, sum + amount, sumOfSquares + (amount * amount));
    }

    public DailyAmountStats plus(DailyAmountStats other) {
        return new DailyAmountStats(count + other.count, sum + other.sum, sumOfSquares + other.sumOfSquares);
    }

    // Backs a single, already-ingested transaction out of this aggregate — mirrors
    // CustomerActivityState.dailySpendTotal's selfAlreadyIngested correction in
    // EvaluationContextBuilder, applied to (count, sum, sumOfSquares) instead of a single sum.
    public DailyAmountStats minus(double amount) {
        return new DailyAmountStats(count - 1, sum - amount, sumOfSquares - (amount * amount));
    }
}
