package com.fraudengine.engine;

import com.fraudengine.config.FraudMetrics;
import com.fraudengine.config.RuleProperties;
import com.fraudengine.model.MerchantLocation;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.repository.TransactionRepository;
import com.fraudengine.streams.CustomerActivityState;
import com.fraudengine.streams.RecentActivityStore;
import com.fraudengine.streams.RecentTransactionRecord;
import com.fraudengine.streams.StoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class EvaluationContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(EvaluationContextBuilder.class);

    private final TransactionRepository transactionRepository;
    private final ReferenceDataCache referenceDataCache;
    private final RuleProperties properties;
    private final Optional<RecentActivityStore> recentActivityStore;
    private final FraudMetrics metrics;

    public EvaluationContextBuilder(TransactionRepository transactionRepository,
                                    ReferenceDataCache referenceDataCache,
                                    RuleProperties properties,
                                    Optional<RecentActivityStore> recentActivityStore,
                                    FraudMetrics metrics) {
        this.transactionRepository = transactionRepository;
        this.referenceDataCache = referenceDataCache;
        this.properties = properties;
        this.recentActivityStore = recentActivityStore;
        this.metrics = metrics;
    }

    public EvaluationContext build(Transaction transaction) {
        RecentActivity recentActivity = loadRecentActivity(transaction);

        Set<String> blacklisted = referenceDataCache.getBlacklistedMerchantIds();

        // For physical-channel transactions with no coordinates, fall back to the
        // merchant's registered location so the geographic rule can still fire.
        // CARD_NOT_PRESENT is excluded — the merchant's address is not a proxy
        // for where the customer physically is during an online transaction.
        Double merchantLat = null;
        Double merchantLon = null;
        if (transaction.getLatitude() == null
                && transaction.getTransactionType() != TransactionType.CARD_NOT_PRESENT) {
            MerchantLocation loc = referenceDataCache
                    .getMerchantLocation(transaction.getMerchantId()).orElse(null);
            if (loc != null) {
                merchantLat = loc.getLatitude();
                merchantLon = loc.getLongitude();
                log.debug("Geographic fallback: resolved merchant '{}' to [{}, {}]",
                        transaction.getMerchantId(), merchantLat, merchantLon);
            }
        }

        // Longer, independent window for personal-baseline statistics — skipped entirely
        // when the rule is disabled to avoid an unnecessary query on the hot path.
        List<Transaction> baselineTransactions = List.of();
        if (properties.getCustomerAmountAnomaly().isEnabled()) {
            Instant baselineStart = transaction.getTimestamp()
                    .minus(properties.getCustomerAmountAnomaly().getLookbackDays(), ChronoUnit.DAYS);
            baselineTransactions = transactionRepository
                    .findRecentByCustomer(transaction.getCustomerId(), baselineStart)
                    .stream()
                    .filter(t -> !t.getId().equals(transaction.getId()))
                    .collect(Collectors.toList());
        }

        log.debug("Evaluation context built: recentTransactions={}, blacklistedMerchants={}, lookbackMinutes={}, dailySpend={}, baselineTransactions={}",
                recentActivity.recent().size(), blacklisted.size(), properties.getContextLookbackMinutes(),
                recentActivity.dailySpend(), baselineTransactions.size());

        return EvaluationContext.builder()
                .recentCustomerTransactions(recentActivity.recent())
                .blacklistedMerchantIds(blacklisted)
                .merchantLatitude(merchantLat)
                .merchantLongitude(merchantLon)
                .dailySpendTotal(recentActivity.dailySpend())
                .customerBaselineTransactions(baselineTransactions)
                .build();
    }

    // recentCustomerTransactions + dailySpendTotal: the two pieces of context that can be
    // served by the Kafka Streams state store. blacklistedMerchantIds/merchant location
    // (Caffeine-cached reference data) and customerBaselineTransactions (a 90-day
    // statistical baseline, a different aggregate shape entirely) are unaffected by this
    // and stay exactly as they were — see the implementation plan's Scope section.
    private record RecentActivity(List<Transaction> recent, BigDecimal dailySpend) {}

    private RecentActivity loadRecentActivity(Transaction transaction) {
        if (recentActivityStore.isPresent()) {
            try {
                CustomerActivityState state = recentActivityStore.get()
                        .lookup(transaction.getCustomerId())
                        .orElse(CustomerActivityState.empty());
                metrics.recordContextFromStreams();
                return fromStreamingState(transaction, state);
            } catch (StoreUnavailableException e) {
                log.warn("customer-activity-store unavailable ({}) — falling back to Postgres for this evaluation",
                        e.getMessage());
            }
        }
        metrics.recordContextFromPostgres();
        return fromPostgres(transaction);
    }

    private RecentActivity fromStreamingState(Transaction transaction, CustomerActivityState state) {
        Instant lookbackStart = transaction.getTimestamp()
                .minus(properties.getContextLookbackMinutes(), ChronoUnit.MINUTES);

        List<RecentTransactionRecord> windowRecords = state.recentTransactionsSince(lookbackStart);

        // The streaming processor is an independent consumer of the same topic
        // (CustomerActivityProcessor) — it may have already ingested this exact
        // transaction by the time this read happens. Excluded the same way the Postgres
        // path excludes it below: filtered out of the list, and — since its amount would
        // otherwise already be folded into the hourly bucket sum too — backed out of the
        // daily total as well. Safe to key this off the same windowRecords lookup: if the
        // current (just-published) transaction has been ingested at all, it is by
        // definition seconds old, so it is always still within this window regardless of
        // how far the two independent consumers have drifted apart.
        boolean selfAlreadyIngested = windowRecords.stream()
                .anyMatch(r -> r.id().equals(transaction.getId()));

        List<Transaction> recent = windowRecords.stream()
                .filter(r -> !r.id().equals(transaction.getId()))
                .map(this::toTransaction)
                .collect(Collectors.toList());

        BigDecimal dailySpend = state.dailySpendTotal(transaction.getTimestamp());
        if (selfAlreadyIngested) {
            dailySpend = dailySpend.subtract(transaction.getAmount());
        }

        return new RecentActivity(recent, dailySpend);
    }

    private RecentActivity fromPostgres(Transaction transaction) {
        Instant lookbackStart = transaction.getTimestamp()
                .minus(properties.getContextLookbackMinutes(), ChronoUnit.MINUTES);

        List<Transaction> recent = transactionRepository
                .findRecentByCustomer(transaction.getCustomerId(), lookbackStart)
                .stream()
                .filter(t -> !t.getId().equals(transaction.getId()))
                .collect(Collectors.toList());

        BigDecimal dailySpend = transactionRepository.sumAmountByCustomerSince(
                transaction.getCustomerId(),
                transaction.getTimestamp().minus(24, ChronoUnit.HOURS),
                transaction.getId());

        return new RecentActivity(recent, dailySpend);
    }

    private Transaction toTransaction(RecentTransactionRecord r) {
        return Transaction.builder()
                .id(r.id())
                .merchantId(r.merchantId())
                .amount(r.amount())
                .currency(r.currency())
                .category(r.category())
                .transactionType(r.transactionType())
                .timestamp(r.timestamp())
                .latitude(r.latitude())
                .longitude(r.longitude())
                .build();
    }
}
