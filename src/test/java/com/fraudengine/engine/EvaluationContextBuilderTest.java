package com.fraudengine.engine;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.model.MerchantLocation;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.repository.TransactionRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvaluationContextBuilderTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private ReferenceDataCache referenceDataCache;

    private RuleProperties properties;
    private EvaluationContextBuilder builder;

    @BeforeEach
    void setUp() {
        properties = new RuleProperties();
        properties.setContextLookbackMinutes(60);
        builder = new EvaluationContextBuilder(transactionRepository, referenceDataCache, properties);

        when(referenceDataCache.getBlacklistedMerchantIds()).thenReturn(Set.of());
        lenient().when(referenceDataCache.getMerchantLocation(any())).thenReturn(Optional.empty());
        when(transactionRepository.sumAmountByCustomerSince(any(), any())).thenReturn(BigDecimal.ZERO);
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
    void blacklistedMerchantIds_populatedFromCache() {
        when(referenceDataCache.getBlacklistedMerchantIds()).thenReturn(Set.of("BAD_M1", "BAD_M2"));
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());

        Transaction tx = tx("C", "M", null, TransactionType.CARD_NOT_PRESENT);
        EvaluationContext ctx = builder.build(tx);

        assertThat(ctx.getBlacklistedMerchantIds()).containsExactlyInAnyOrder("BAD_M1", "BAD_M2");
    }

    @Test
    void dailySpend_queriedWithCorrectCustomerAnd24hWindow() {
        Transaction tx = tx("CUST_2", "M1", null, TransactionType.CARD_NOT_PRESENT);
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());
        when(transactionRepository.sumAmountByCustomerSince(eq("CUST_2"), any()))
                .thenReturn(new BigDecimal("1500.00"));

        EvaluationContext ctx = builder.build(tx);

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(transactionRepository).sumAmountByCustomerSince(eq("CUST_2"), cutoffCaptor.capture());

        Instant expected24hCutoff = tx.getTimestamp().minus(24, ChronoUnit.HOURS);
        assertThat(cutoffCaptor.getValue()).isCloseTo(expected24hCutoff, org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
        assertThat(ctx.getDailySpendTotal()).isEqualByComparingTo("1500.00");
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
        // Transaction already has coords — no fallback needed
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
    void customerBaselineTransactions_loadedFromLongerWindow() {
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

        assertThat(ctx.getCustomerBaselineTransactions()).containsExactly(other);
    }

    @Test
    void customerAmountAnomalyDisabled_baselineNotQueried() {
        properties.getCustomerAmountAnomaly().setEnabled(false);
        Transaction tx = tx("CUST_1", "M1", null, TransactionType.CARD_NOT_PRESENT);
        when(transactionRepository.findRecentByCustomer(any(), any())).thenReturn(List.of());

        EvaluationContext ctx = builder.build(tx);

        verify(transactionRepository, times(1)).findRecentByCustomer(any(), any());
        assertThat(ctx.getCustomerBaselineTransactions()).isEmpty();
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
