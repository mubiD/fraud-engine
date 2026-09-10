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
//   - recentTransactions: a pruned record list, bounded to contextLookbackMinutes.
//     Serves every list-filter rule (Velocity, CrossMerchantVelocity, Duplicate,
//     CardCloning, MultiChannel, Geographic) plus CumulativeSpendingRule's hourly leg
//     (hourly-window-minutes defaults to 60, same as contextLookbackMinutes).
//   - hourlySpendBuckets: epoch-hour -> currency -> summed amount, pruned to the trailing
//     24 buckets, serving CumulativeSpendingRule's daily leg. A running list of every
//     transaction over 24h would be a much larger and mostly-unused piece of hot state for
//     a single rule's one aggregate number; hourly-granularity buckets are the standard
//     rolling-sum technique for this (see the plan's "Option B" rationale) and are more
//     than sufficient precision for a fraud heuristic, not an accounting figure. Keyed by
//     currency (not just bucket) so a customer transacting in more than one currency never
//     gets those amounts pooled as equivalent magnitude. Found live 2026-09-09 as a real
//     gap: unlike DuplicateTransactionRule (which always compared currency), the daily/
//     hourly spend aggregates and the amount-anomaly baseline below did not, and neither
//     was it documented as a scoped-out tradeoff in DESIGN.md the way every other
//     limitation in this file is.
//   - dailyAmountBuckets: epoch-day -> currency -> (count, sum, sumOfSquares), pruned to
//     the configured lookback (default 90 daily buckets). Serves
//     CustomerAmountAnomalyRule's personal mean/stdDev baseline. Same rationale as
//     hourlySpendBuckets, scaled to a 90-day window, and the same per-currency keying for
//     the same reason.
//
// Both the append-time pruning (bounds how much state accumulates per customer) and the
// read-time re-filtering (recentTransactionsSince / dailySpendTotal / baselineAggregate,
// below) are independently correct against an arbitrary "as of" instant: the writer's
// anchor (the event it just processed) and the reader's anchor (the transaction currently
// being evaluated) are not guaranteed to be the same instant, since the Kafka Streams app
// and TransactionConsumer are independent consumers.
public record CustomerActivityState(
        List<RecentTransactionRecord> recentTransactions,
        Map<Long, Map<String, BigDecimal>> hourlySpendBuckets,
        Map<Long, Map<String, DailyAmountStats>> dailyAmountBuckets) {

    private static final int MAX_HOURLY_BUCKETS = 24;

    @JsonCreator
    public CustomerActivityState(
            @JsonProperty("recentTransactions") List<RecentTransactionRecord> recentTransactions,
            @JsonProperty("hourlySpendBuckets") Map<Long, Map<String, BigDecimal>> hourlySpendBuckets,
            @JsonProperty("dailyAmountBuckets") Map<Long, Map<String, DailyAmountStats>> dailyAmountBuckets) {
        this.recentTransactions = recentTransactions;
        this.hourlySpendBuckets = hourlySpendBuckets;
        this.dailyAmountBuckets = dailyAmountBuckets;
    }

    public static CustomerActivityState empty() {
        return new CustomerActivityState(List.of(), Map.of(), Map.of());
    }

    // Appends `record`, then prunes all three structures relative to record's own timestamp
    // (event time, not processing time, matching EvaluationContextBuilder's existing
    // anchor-on-event-time semantics). baselineLookbackDays governs dailyAmountBuckets'
    // retention window, independent of recentWindow, which is typically much shorter
    // (contextLookbackMinutes, default 60 minutes vs. this, default 90 days).
    //
    // Idempotency guard: this Kafka Streams app runs at-least-once (no processing.guarantee
    // override), so a rebalance, restart, or a redelivered offset can hand the same record to
    // CustomerActivityProcessor twice. Unlike TransactionConsumer's primary path — which has
    // an explicit, documented idempotency guard (findByIdOnly + a unique constraint backstop)
    // for exactly this class of problem — this store had none until 2026-09-10: a duplicate
    // delivery silently double-appended to recentTransactions (inflating VELOCITY/
    // CROSS_MERCHANT_VELOCITY counts and manufacturing a false DUPLICATE_TRANSACTION, since
    // two identical entries at the same timestamp/amount/merchant look exactly like that
    // rule's target pattern) and double-merged into both bucket aggregates (skewing
    // CUMULATIVE_SPENDING for up to an hour, and CUSTOMER_AMOUNT_ANOMALY's baseline for up to
    // the full lookback window — the quietest, longest-lived form of this bug). Bounded fix:
    // if this id is already present in recentTransactions, it was already folded into every
    // structure here, so no-op. Only protects against redelivery while the original is still
    // within recentWindow — a redelivery arriving after it has aged out of that list would
    // still double-count in the bucket aggregates, but that requires a redelivery lag far
    // longer than any realistic rebalance/restart, not the failure mode this guards against.
    public CustomerActivityState withAppended(RecentTransactionRecord record, Duration recentWindow,
                                               int baselineLookbackDays) {
        boolean alreadyRecorded = recentTransactions.stream()
                .anyMatch(r -> r.id().equals(record.id()));
        if (alreadyRecorded) {
            return this;
        }

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

        Map<Long, Map<String, BigDecimal>> prunedHourlyBuckets = new HashMap<>();
        for (Map.Entry<Long, Map<String, BigDecimal>> e : hourlySpendBuckets.entrySet()) {
            if (e.getKey() > hourBucketCutoff) {
                prunedHourlyBuckets.put(e.getKey(), new HashMap<>(e.getValue()));
            }
        }
        prunedHourlyBuckets.computeIfAbsent(currentHourBucket, k -> new HashMap<>())
                .merge(record.currency(), record.amount(), BigDecimal::add);

        long currentDayBucket = dayBucket(anchor);
        long dayBucketCutoff = currentDayBucket - baselineLookbackDays;

        Map<Long, Map<String, DailyAmountStats>> prunedDailyBuckets = new HashMap<>();
        for (Map.Entry<Long, Map<String, DailyAmountStats>> e : dailyAmountBuckets.entrySet()) {
            if (e.getKey() > dayBucketCutoff) {
                prunedDailyBuckets.put(e.getKey(), new HashMap<>(e.getValue()));
            }
        }
        prunedDailyBuckets.computeIfAbsent(currentDayBucket, k -> new HashMap<>())
                .merge(record.currency(), DailyAmountStats.empty().plus(record.amount().doubleValue()),
                        DailyAmountStats::plus);

        Map<Long, Map<String, BigDecimal>> frozenHourly = new HashMap<>();
        prunedHourlyBuckets.forEach((k, v) -> frozenHourly.put(k, Map.copyOf(v)));

        Map<Long, Map<String, DailyAmountStats>> frozenDaily = new HashMap<>();
        prunedDailyBuckets.forEach((k, v) -> frozenDaily.put(k, Map.copyOf(v)));

        return new CustomerActivityState(
                List.copyOf(prunedList), Map.copyOf(frozenHourly), Map.copyOf(frozenDaily));
    }

    // Read-side re-filter: independent of whatever the last write happened to prune to.
    public List<RecentTransactionRecord> recentTransactionsSince(Instant windowStart) {
        return recentTransactions.stream()
                .filter(r -> r.timestamp().isAfter(windowStart))
                .toList();
    }

    // Read-side re-filter for the daily spend leg, as-of an arbitrary instant, scoped to a
    // single currency so a customer transacting in more than one currency never gets those
    // amounts pooled as equivalent magnitude.
    public BigDecimal dailySpendTotal(Instant asOf, String currency) {
        long cutoff = hourBucket(asOf) - MAX_HOURLY_BUCKETS;
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Map<String, BigDecimal>> e : hourlySpendBuckets.entrySet()) {
            if (e.getKey() > cutoff) {
                BigDecimal forCurrency = e.getValue().get(currency);
                if (forCurrency != null) {
                    total = total.add(forCurrency);
                }
            }
        }
        return total;
    }

    // Read-side re-filter for CustomerAmountAnomalyRule's baseline, as-of an arbitrary
    // instant and an arbitrary lookback (independent of whatever window the last write
    // happened to prune buckets to, same pattern as dailySpendTotal above), scoped to a
    // single currency for the same reason. Returns the raw (count, sum, sumOfSquares)
    // aggregate; AmountBaselineStats.of(...) derives mean/stdDev from it.
    public DailyAmountStats baselineAggregate(Instant asOf, int lookbackDays, String currency) {
        long cutoff = dayBucket(asOf) - lookbackDays;
        DailyAmountStats total = DailyAmountStats.empty();
        for (Map.Entry<Long, Map<String, DailyAmountStats>> e : dailyAmountBuckets.entrySet()) {
            if (e.getKey() > cutoff) {
                DailyAmountStats forCurrency = e.getValue().get(currency);
                if (forCurrency != null) {
                    total = total.plus(forCurrency);
                }
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
