package com.fraudengine.streams;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Value type stored in the Kafka Streams "customer-activity-store", one per customerId.
// Three independent structures, matching the three different aggregate shapes
// EvaluationContext needs:
//   - recentTransactions: a pruned record list, bounded to contextLookbackMinutes —
//     serves every list-filter rule (Velocity, CrossMerchantVelocity, Duplicate,
//     CardCloning, MultiChannel, Geographic) plus CumulativeSpendingRule's hourly leg
//     (hourly-window-minutes defaults to 60, same as contextLookbackMinutes).
//   - hourlySpendBuckets: epoch-hour -> summed amount, pruned to the trailing 24 buckets —
//     serves CumulativeSpendingRule's daily leg. A running list of every transaction over
//     24h would be a much larger and mostly-unused piece of hot state for a single rule's
//     one aggregate number; hourly-granularity buckets are the standard rolling-sum
//     technique for this (see the plan's "Option B" rationale) and are more than
//     sufficient precision for a fraud heuristic, not an accounting figure.
//   - dailyAmountBuckets: epoch-day -> (count, sum, sumOfSquares), pruned to the configured
//     lookback (default 90 daily buckets) — serves CustomerAmountAnomalyRule's personal
//     mean/stdDev baseline. Same rationale as hourlySpendBuckets, scaled to a 90-day window:
//     retaining every raw transaction over 90 days per customer would be a much larger and
//     mostly-unused piece of hot state for two derived numbers (mean, stdDev); daily
//     aggregates are more than sufficient precision for this heuristic.
//
// Both the append-time pruning (bounds how much state accumulates per customer) and the
// read-time re-filtering (recentTransactionsSince / dailySpendTotal / baselineAggregate,
// below) are independently correct against an arbitrary "as of" instant — the writer's
// anchor (the event it just processed) and the reader's anchor (the transaction currently
// being evaluated) are not guaranteed to be the same instant, since the Kafka Streams app
// and TransactionConsumer are independent consumers.
public record CustomerActivityState(
        List<RecentTransactionRecord> recentTransactions,
        Map<Long, BigDecimal> hourlySpendBuckets,
        Map<Long, DailyAmountStats> dailyAmountBuckets) {

    private static final int MAX_HOURLY_BUCKETS = 24;

    @JsonCreator
    public CustomerActivityState(
            @JsonProperty("recentTransactions") List<RecentTransactionRecord> recentTransactions,
            @JsonProperty("hourlySpendBuckets") Map<Long, BigDecimal> hourlySpendBuckets,
            @JsonProperty("dailyAmountBuckets") Map<Long, DailyAmountStats> dailyAmountBuckets) {
        this.recentTransactions = recentTransactions;
        this.hourlySpendBuckets = hourlySpendBuckets;
        this.dailyAmountBuckets = dailyAmountBuckets;
    }

    public static CustomerActivityState empty() {
        return new CustomerActivityState(List.of(), Map.of(), Map.of());
    }

    // Appends `record`, then prunes all three structures relative to record's own timestamp
    // (event time, not processing time — matches EvaluationContextBuilder's existing
    // anchor-on-event-time semantics). baselineLookbackDays governs dailyAmountBuckets'
    // retention window — independent of recentWindow, which is typically much shorter
    // (contextLookbackMinutes, default 60 minutes vs. this, default 90 days).
    public CustomerActivityState withAppended(RecentTransactionRecord record, Duration recentWindow,
                                               int baselineLookbackDays) {
        Instant anchor = record.timestamp();
        Instant listCutoff = anchor.minus(recentWindow);

        List<RecentTransactionRecord> prunedList = new ArrayList<>(recentTransactions.size() + 1);
        for (RecentTransactionRecord r : recentTransactions) {
            if (r.timestamp().isAfter(listCutoff)) {
                prunedList.add(r);
            }
        }
        prunedList.add(record);

        long currentHourBucket = hourBucket(anchor);
        long hourBucketCutoff = currentHourBucket - MAX_HOURLY_BUCKETS;

        Map<Long, BigDecimal> prunedHourlyBuckets = new HashMap<>();
        for (Map.Entry<Long, BigDecimal> e : hourlySpendBuckets.entrySet()) {
            if (e.getKey() > hourBucketCutoff) {
                prunedHourlyBuckets.put(e.getKey(), e.getValue());
            }
        }
        prunedHourlyBuckets.merge(currentHourBucket, record.amount(), BigDecimal::add);

        long currentDayBucket = dayBucket(anchor);
        long dayBucketCutoff = currentDayBucket - baselineLookbackDays;

        Map<Long, DailyAmountStats> prunedDailyBuckets = new HashMap<>();
        for (Map.Entry<Long, DailyAmountStats> e : dailyAmountBuckets.entrySet()) {
            if (e.getKey() > dayBucketCutoff) {
                prunedDailyBuckets.put(e.getKey(), e.getValue());
            }
        }
        prunedDailyBuckets.merge(currentDayBucket, DailyAmountStats.empty().plus(record.amount().doubleValue()),
                DailyAmountStats::plus);

        return new CustomerActivityState(
                List.copyOf(prunedList), Map.copyOf(prunedHourlyBuckets), Map.copyOf(prunedDailyBuckets));
    }

    // Read-side re-filter: independent of whatever the last write happened to prune to.
    public List<RecentTransactionRecord> recentTransactionsSince(Instant windowStart) {
        return recentTransactions.stream()
                .filter(r -> r.timestamp().isAfter(windowStart))
                .toList();
    }

    // Read-side re-filter for the daily spend leg, as-of an arbitrary instant.
    public BigDecimal dailySpendTotal(Instant asOf) {
        long cutoff = hourBucket(asOf) - MAX_HOURLY_BUCKETS;
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> e : hourlySpendBuckets.entrySet()) {
            if (e.getKey() > cutoff) {
                total = total.add(e.getValue());
            }
        }
        return total;
    }

    // Read-side re-filter for CustomerAmountAnomalyRule's baseline, as-of an arbitrary
    // instant and an arbitrary lookback (independent of whatever window the last write
    // happened to prune buckets to — same pattern as dailySpendTotal above). Returns the
    // raw (count, sum, sumOfSquares) aggregate; AmountBaselineStats.of(...) derives
    // mean/stdDev from it.
    public DailyAmountStats baselineAggregate(Instant asOf, int lookbackDays) {
        long cutoff = dayBucket(asOf) - lookbackDays;
        DailyAmountStats total = DailyAmountStats.empty();
        for (Map.Entry<Long, DailyAmountStats> e : dailyAmountBuckets.entrySet()) {
            if (e.getKey() > cutoff) {
                total = total.plus(e.getValue());
            }
        }
        return total;
    }

    private static long hourBucket(Instant instant) {
        return instant.getEpochSecond() / 3600;
    }

    private static long dayBucket(Instant instant) {
        return instant.getEpochSecond() / 86400;
    }
}
