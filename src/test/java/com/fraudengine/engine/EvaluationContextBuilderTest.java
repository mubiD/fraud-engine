package com.fraudengine.engine;

import com.fraudengine.config.FraudMetrics;
import com.fraudengine.config.RuleProperties;
import com.fraudengine.model.MerchantLocation;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.repository.TransactionRepository;
import com.fraudengine.streams.CustomerActivityState;
import com.fraudengine.streams.DailyAmountStats;
import com.fraudengine.streams.RecentActivityStore;
import com.fraudengine.streams.RecentTransactionRecord;
import com.fraudengine.streams.StoreUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvaluationContextBuilderTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private ReferenceDataCache referenceDataCache;
    @Mock private FraudMetrics metrics;
    @Mock private RecentActivityStore recentActivityStore;

    private RuleProperties properties;
    private EvaluationContextBuilder builder;

    @BeforeEach
    void setUp() {
        properties = new RuleProperties();
        properties.setContextLookbackMinutes(60);
        // Optional.empty() here mirrors standalone/local, where no RecentActivityStore
        // bean is registered at all (see KafkaStreamsRecentActivityStore's @Profile) —
        // every pre-existing test in this class exercises the Postgres path unchanged.
        // The streaming path is covered separately below, with its own builder instance.
        builder = new EvaluationContextBuilder(
                transactionRepository, referenceDataCache, properties, Optional.empty(), metrics);

        lenient().when(referenceDataCache.getMerchantLocation(any())).thenReturn(Optional.empty());
        // lenient: unused by the streaming-path tests below, which never reach Postgres.
        lenient().when(transactionRepository.sumAmountByCustomerSince(any(), any(), any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void recentTransactions_loadedForCorrectCustomerAndWindow() {
        // Isolate the primary 60-min window from the separate customer-baseline query
        // (also findRecentByCustomer), which would otherwise make this a 2-invocation call.
        properties.getCustomerAmountAnomaly().setEnabled(false);
        Transaction tx = tx("CUST_1", "M1", null, TransactionType.CARD_NOT_PRESENT);
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());

        builder.build(tx);

        ArgumentCaptor<Instant> windowCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(transactionRepository).findRecentByCustomer(eq("CUST_1"), windowCaptor.capture());

        Instant expectedLookback = tx.getTimestamp().minus(60, ChronoUnit.MINUTES);
        assertThat(windowCaptor.getValue()).isCloseTo(expectedLookback, org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
    }

    @Test
    void currentTransaction_excludedFromRecentList() {
        Transaction tx = tx("CUST_1", "M1", null, TransactionType.CARD_NOT_PRESENT);
        Transaction other = tx("CUST_1", "M2", null, TransactionType.CARD_NOT_PRESENT);

        // repo returns the current tx plus another — current must be filtered out
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of(tx, other));

        EvaluationContext ctx = builder.build(tx);

        assertThat(ctx.getRecentCustomerTransactions()).containsExactly(other);
    }

    @Test
    void dailySpend_queriedWithCorrectCustomerAnd24hWindow() {
        Transaction tx = tx("CUST_2", "M1", null, TransactionType.CARD_NOT_PRESENT);
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());
        when(transactionRepository.sumAmountByCustomerSince(eq("CUST_2"), any(), any()))
                .thenReturn(new BigDecimal("1500.00"));

        EvaluationContext ctx = builder.build(tx);

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(transactionRepository).sumAmountByCustomerSince(eq("CUST_2"), cutoffCaptor.capture(), any());

        Instant expected24hCutoff = tx.getTimestamp().minus(24, ChronoUnit.HOURS);
        assertThat(cutoffCaptor.getValue()).isCloseTo(expected24hCutoff, org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
        assertThat(ctx.getDailySpendTotal()).isEqualByComparingTo("1500.00");
    }

    @Test
    void dailySpend_excludesCurrentTransactionFromSum() {
        // Regression test: the current transaction is persisted before rule evaluation runs
        // (TransactionConsumer saves it, then calls RuleEngine.evaluate()), so it's already
        // inside the 24h window by the time this query runs. Without excluding its own id,
        // CumulativeSpendingRule would double-count it (context.getDailySpendTotal() already
        // includes it, then the rule adds currentAmount again on top).
        Transaction tx = tx("CUST_3", "M1", null, TransactionType.CARD_NOT_PRESENT);
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());
        when(transactionRepository.sumAmountByCustomerSince(any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        builder.build(tx);

        verify(transactionRepository).sumAmountByCustomerSince(eq("CUST_3"), any(), eq(tx.getId()));
    }

    @Test
    void physicalTxWithNoCoords_merchantLocationFallbackApplied() {
        Transaction tx = tx("C", "M_GEO", null, TransactionType.CARD_PRESENT);

        MerchantLocation loc = new MerchantLocation();
        loc.setMerchantId("M_GEO");
        loc.setLatitude(-33.9249);
        loc.setLongitude(18.4241);
        when(referenceDataCache.getMerchantLocation("M_GEO")).thenReturn(Optional.of(loc));
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());

        EvaluationContext ctx = builder.build(tx);

        assertThat(ctx.getMerchantLatitude()).isEqualTo(-33.9249);
        assertThat(ctx.getMerchantLongitude()).isEqualTo(18.4241);
    }

    @Test
    void contactlessTxWithNoCoords_merchantLocationFallbackApplied() {
        Transaction tx = tx("C", "M_GEO", null, TransactionType.CONTACTLESS);

        MerchantLocation loc = new MerchantLocation();
        loc.setMerchantId("M_GEO");
        loc.setLatitude(51.5074);
        loc.setLongitude(-0.1278);
        when(referenceDataCache.getMerchantLocation("M_GEO")).thenReturn(Optional.of(loc));
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());

        EvaluationContext ctx = builder.build(tx);

        assertThat(ctx.getMerchantLatitude()).isEqualTo(51.5074);
    }

    @Test
    void cardNotPresentTxWithNoCoords_merchantLocationNotQueried() {
        Transaction tx = tx("C", "M1", null, TransactionType.CARD_NOT_PRESENT);
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());

        EvaluationContext ctx = builder.build(tx);

        verify(referenceDataCache, never()).getMerchantLocation(any());
        assertThat(ctx.getMerchantLatitude()).isNull();
        assertThat(ctx.getMerchantLongitude()).isNull();
    }

    @Test
    void txWithCoordinatesPresent_merchantLocationNotQueried() {
        Transaction tx = Transaction.builder()
                .id(UUID.randomUUID()).customerId("C").merchantId("M1")
                .amount(BigDecimal.TEN).currency("ZAR")
                .transactionType(TransactionType.CARD_PRESENT)
                .latitude(-26.2041).longitude(28.0473)
                .timestamp(Instant.now()).build();
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());

        EvaluationContext ctx = builder.build(tx);

        verify(referenceDataCache, never()).getMerchantLocation(any());
        // coords come from the transaction, not the context (context only holds fallback coords)
        assertThat(ctx.getMerchantLatitude()).isNull();
        assertThat(ctx.getMerchantLongitude()).isNull();
    }

    @Test
    void customerAmountBaseline_loadedFromLongerWindow() {
        properties.getCustomerAmountAnomaly().setEnabled(true);
        properties.getCustomerAmountAnomaly().setLookbackDays(90);
        Transaction tx = tx("CUST_3", "M1", null, TransactionType.CARD_NOT_PRESENT);
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());

        builder.build(tx);

        ArgumentCaptor<Instant> sinceCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(transactionRepository, times(2)).findRecentByCustomer(eq("CUST_3"), sinceCaptor.capture());

        Instant expectedBaselineStart = tx.getTimestamp().minus(90, ChronoUnit.DAYS);
        assertThat(sinceCaptor.getAllValues())
                .anySatisfy(since -> assertThat(since)
                        .isCloseTo(expectedBaselineStart, org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS)));
    }

    @Test
    void currentTransaction_excludedFromBaselineList() {
        Transaction tx = tx("CUST_1", "M1", null, TransactionType.CARD_NOT_PRESENT);
        Transaction other = tx("CUST_1", "M2", null, TransactionType.CARD_NOT_PRESENT);

        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of(tx, other));

        EvaluationContext ctx = builder.build(tx);

        // Both tx and other carry the same amount (see the tx() helper), so a baseline built
        // from [other] alone (tx excluded) has exactly one entry — proof tx was filtered out.
        assertThat(ctx.getCustomerAmountBaseline().count()).isEqualTo(1);
    }

    @Test
    void customerAmountAnomalyDisabled_baselineNotQueried() {
        properties.getCustomerAmountAnomaly().setEnabled(false);
        Transaction tx = tx("CUST_1", "M1", null, TransactionType.CARD_NOT_PRESENT);
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());

        EvaluationContext ctx = builder.build(tx);

        verify(transactionRepository, times(1)).findRecentByCustomer(any(), any());
        assertThat(ctx.getCustomerAmountBaseline()).isEqualTo(AmountBaselineStats.empty());
    }

    @Test
    void merchantLocationAbsent_coordinatesRemainsNull() {
        Transaction tx = tx("C", "UNKNOWN_MERCHANT", null, TransactionType.CARD_PRESENT);
        when(referenceDataCache.getMerchantLocation("UNKNOWN_MERCHANT")).thenReturn(Optional.empty());
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());

        EvaluationContext ctx = builder.build(tx);

        assertThat(ctx.getMerchantLatitude()).isNull();
        assertThat(ctx.getMerchantLongitude()).isNull();
    }

    // ---- RecentActivityStore (Kafka Streams) path ----

    private EvaluationContextBuilder streamingBuilder() {
        return new EvaluationContextBuilder(
                transactionRepository, referenceDataCache, properties,
                Optional.of(recentActivityStore), metrics);
    }

    @Test
    void streamingStorePresent_usedInsteadOfPostgres_forRecentAndDailySpend() {
        properties.getCustomerAmountAnomaly().setEnabled(false);
        Transaction tx = tx("CUST_1", "M1", null, TransactionType.CARD_NOT_PRESENT);
        Transaction current = tx;
        RecentTransactionRecord other = new RecentTransactionRecord(
                UUID.randomUUID(), "M2", new BigDecimal("42.00"), "ZAR", "RETAIL",
                TransactionType.CARD_NOT_PRESENT, current.getTimestamp().minus(5, ChronoUnit.MINUTES), null, null);
        CustomerActivityState state = new CustomerActivityState(
                List.of(other), Map.of(), Map.of());
        when(recentActivityStore.lookup("CUST_1")).thenReturn(Optional.of(state));

        EvaluationContext ctx = streamingBuilder().build(current);

        assertThat(ctx.getRecentCustomerTransactions()).hasSize(1);
        assertThat(ctx.getRecentCustomerTransactions().get(0).getMerchantId()).isEqualTo("M2");
        verifyNoInteractions(transactionRepository);
        verify(metrics).recordContextFromStreams();
        verify(metrics, never()).recordContextFromPostgres();
    }

    @Test
    void streamingStoreThrowsUnavailable_fallsBackToPostgres() {
        properties.getCustomerAmountAnomaly().setEnabled(false);
        Transaction tx = tx("CUST_1", "M1", null, TransactionType.CARD_NOT_PRESENT);
        when(recentActivityStore.lookup("CUST_1")).thenThrow(new StoreUnavailableException("not ready"));
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());

        EvaluationContext ctx = streamingBuilder().build(tx);

        assertThat(ctx.getRecentCustomerTransactions()).isEmpty();
        verify(transactionRepository).findRecentByCustomer(eq("CUST_1"), any());
        verify(metrics).recordContextFromPostgres();
        verify(metrics, never()).recordContextFromStreams();
    }

    @Test
    void streamingStore_selfAlreadyIngested_excludedFromListAndDailySpend() {
        // The Kafka Streams app is an independent consumer of the same topic (see
        // CustomerActivityProcessor) — it can ingest the current transaction before this
        // read happens. Regression coverage for the same double-count risk the Postgres
        // path already guards against (see dailySpend_excludesCurrentTransactionFromSum).
        properties.getCustomerAmountAnomaly().setEnabled(false);
        Transaction tx = tx("CUST_1", "M1", null, TransactionType.CARD_NOT_PRESENT);
        RecentTransactionRecord self = new RecentTransactionRecord(
                tx.getId(), tx.getMerchantId(), tx.getAmount(), tx.getCurrency(), tx.getCategory(),
                tx.getTransactionType(), tx.getTimestamp(), null, null);
        long bucket = tx.getTimestamp().getEpochSecond() / 3600;
        CustomerActivityState state = new CustomerActivityState(
                List.of(self), Map.of(bucket, tx.getAmount()), Map.of());
        when(recentActivityStore.lookup("CUST_1")).thenReturn(Optional.of(state));

        EvaluationContext ctx = streamingBuilder().build(tx);

        assertThat(ctx.getRecentCustomerTransactions()).isEmpty();
        assertThat(ctx.getDailySpendTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void streamingStorePresent_usedForBaseline_whenEnabled() {
        properties.getCustomerAmountAnomaly().setEnabled(true);
        Transaction tx = tx("CUST_1", "M1", null, TransactionType.CARD_NOT_PRESENT);
        long dayBucket = tx.getTimestamp().getEpochSecond() / 86400;
        // Five prior 100.00 amounts on the same day-bucket -> mean 100, stdDev 0.
        DailyAmountStats bucketStats = DailyAmountStats.empty()
                .plus(100.0).plus(100.0).plus(100.0).plus(100.0).plus(100.0);
        CustomerActivityState state = new CustomerActivityState(
                List.of(), Map.of(), Map.of(dayBucket, bucketStats));
        when(recentActivityStore.lookup("CUST_1")).thenReturn(Optional.of(state));

        EvaluationContext ctx = streamingBuilder().build(tx);

        assertThat(ctx.getCustomerAmountBaseline().count()).isEqualTo(5);
        assertThat(ctx.getCustomerAmountBaseline().mean()).isEqualTo(100.0);
        assertThat(ctx.getCustomerAmountBaseline().stdDev()).isEqualTo(0.0);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void streamingStore_selfAlreadyIngested_excludedFromBaseline() {
        properties.getCustomerAmountAnomaly().setEnabled(true);
        Transaction tx = tx("CUST_1", "M1", null, TransactionType.CARD_NOT_PRESENT);
        RecentTransactionRecord self = new RecentTransactionRecord(
                tx.getId(), tx.getMerchantId(), tx.getAmount(), tx.getCurrency(), tx.getCategory(),
                tx.getTransactionType(), tx.getTimestamp(), null, null);
        long dayBucket = tx.getTimestamp().getEpochSecond() / 86400;
        // Four prior 100.00 amounts plus tx itself (already ingested) folded into the same
        // day bucket — the correction must back tx's own amount back out before deriving stats.
        DailyAmountStats bucketStats = DailyAmountStats.empty()
                .plus(100.0).plus(100.0).plus(100.0).plus(100.0)
                .plus(tx.getAmount().doubleValue());
        CustomerActivityState state = new CustomerActivityState(
                List.of(self), Map.of(), Map.of(dayBucket, bucketStats));
        when(recentActivityStore.lookup("CUST_1")).thenReturn(Optional.of(state));

        EvaluationContext ctx = streamingBuilder().build(tx);

        assertThat(ctx.getCustomerAmountBaseline().count()).isEqualTo(4);
        assertThat(ctx.getCustomerAmountBaseline().mean()).isEqualTo(100.0);
    }

    private Transaction tx(String customerId, String merchantId, Double latitude, TransactionType type) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .customerId(customerId)
                .merchantId(merchantId)
                .amount(BigDecimal.TEN)
                .currency("ZAR")
                .latitude(latitude)
                .transactionType(type)
                .timestamp(Instant.now())
                .build();
    }
}
