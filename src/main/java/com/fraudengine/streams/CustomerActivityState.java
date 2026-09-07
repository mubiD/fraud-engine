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
// Two independent structures, matching the two different aggregate shapes
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
//
// Both the append-time pruning (bounds how much state accumulates per customer) and the
// read-time re-filtering (recentTransactionsSince / dailySpendTotal, below) are
// independently correct against an arbitrary "as of" instant — the writer's anchor
// (the event it just processed) and the reader's anchor (the transaction currently being
// evaluated) are not guaranteed to be the same instant, since the Kafka Streams app and
// TransactionConsumer are independent consumers.
public record CustomerActivityState(
        List<RecentTransactionRecord> recentTransactions,
        Map<Long, BigDecimal> hourlySpendBuckets) {

    private static final int MAX_HOURLY_BUCKETS = 24;

    @JsonCreator
    public CustomerActivityState(
            @JsonProperty("recentTransactions") List<RecentTransactionRecord> recentTransactions,
            @JsonProperty("hourlySpendBuckets") Map<Long, BigDecimal> hourlySpendBuckets) {
        this.recentTransactions = recentTransactions;
        this.hourlySpendBuckets = hourlySpendBuckets;
    }

    public static CustomerActivityState empty() {
        return new CustomerActivityState(List.of(), Map.of());
    }

    // Appends `record`, then prunes both structures relative to record's own timestamp
    // (event time, not processing time — matches EvaluationContextBuilder's existing
    // anchor-on-event-time semantics).
    public CustomerActivityState withAppended(RecentTransactionRecord record, Duration recentWindow) {
        Instant anchor = record.timestamp();
        Instant listCutoff = anchor.minus(recentWindow);

        List<RecentTransactionRecord> prunedList = new ArrayList<>(recentTransactions.size() + 1);
        for (RecentTransactionRecord r : recentTransactions) {
            if (r.timestamp().isAfter(listCutoff)) {
                prunedList.add(r);
            }
        }
        prunedList.add(record);

        long currentBucket = hourBucket(anchor);
        long bucketCutoff = currentBucket - MAX_HOURLY_BUCKETS;

        Map<Long, BigDecimal> prunedBuckets = new HashMap<>();
        for (Map.Entry<Long, BigDecimal> e : hourlySpendBuckets.entrySet()) {
            if (e.getKey() > bucketCutoff) {
                prunedBuckets.put(e.getKey(), e.getValue());
            }
        }
        prunedBuckets.merge(currentBucket, record.amount(), BigDecimal::add);

        return new CustomerActivityState(List.copyOf(prunedList), Map.copyOf(prunedBuckets));
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

    private static long hourBucket(Instant instant) {
        return instant.getEpochSecond() / 3600;
    }
}
