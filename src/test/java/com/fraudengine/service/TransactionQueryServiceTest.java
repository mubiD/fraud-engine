package com.fraudengine.service;

import com.fraudengine.api.dto.CustomerRiskSummaryDto;
import com.fraudengine.api.dto.FraudSummaryDto;
import com.fraudengine.api.dto.MerchantRiskSummaryDto;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Disposition;
import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.repository.FraudAssessmentRepository;
import com.fraudengine.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionQueryServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock FraudAssessmentRepository assessmentRepository;
    @Mock RuleManagementService ruleManagementService;

    TransactionQueryService service;

    static final String CUSTOMER   = "CUST-001";
    static final String MERCHANT   = "MERCH-001";
    static final Instant FROM      = Instant.parse("2026-07-01T00:00:00Z");
    static final Instant TO        = Instant.parse("2026-07-31T23:59:59Z");
    static final Instant CURSOR_TS = Instant.parse("2026-07-15T12:00:00Z");
    static final UUID CURSOR_ID    = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        service = new TransactionQueryService(transactionRepository, assessmentRepository, ruleManagementService);
    }

    private FraudRule mockRule(String name) {
        FraudRule rule = mock(FraudRule.class);
        when(rule.getRuleName()).thenReturn(name);
        return rule;
    }

    // ── validateDateRange ────────────────────────────────────────────────────

    @Test
    void getByCustomerId_toBeforeFrom_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.getByCustomerId(CUSTOMER, TO, FROM, null, null, 20, "desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'to'")
                .hasMessageContaining("'from'");
    }

    @Test
    void getFlagged_toBeforeFrom_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.getFlagged(null, null, null, null, TO, FROM, null, null, 20, "desc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getPassed_toBeforeFrom_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.getPassed(null, null, TO, FROM, null, null, 20, "desc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getFlaggedByMerchant_toBeforeFrom_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.getFlaggedByMerchant(MERCHANT, null, null, TO, FROM, null, null, 20, "desc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getFraudSummary_toBeforeFrom_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.getFraudSummary(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getByCustomerId_equalFromAndTo_isAccepted() {
        when(transactionRepository.findByCustomerInRange(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of()));
        // same instant for both — should not throw
        service.getByCustomerId(CUSTOMER, FROM, FROM, null, null, 20, "desc");
    }

    // ── getByCustomerId ──────────────────────────────────────────────────────

    @Test
    void getByCustomerId_delegatesToRepository() {
        Slice<Transaction> expected = new SliceImpl<>(List.of());
        when(transactionRepository.findByCustomerInRange(
                eq(CUSTOMER), eq(FROM), eq(TO), eq(CURSOR_TS), eq(CURSOR_ID), any(PageRequest.class)))
                .thenReturn(expected);

        Slice<Transaction> result = service.getByCustomerId(CUSTOMER, FROM, TO, CURSOR_TS, CURSOR_ID, 10, "desc");

        assertThat(result).isSameAs(expected);
        verify(transactionRepository).findByCustomerInRange(
                CUSTOMER, FROM, TO, CURSOR_TS, CURSOR_ID, PageRequest.of(0, 10));
    }

    @Test
    void getByCustomerId_zeroPageSize_usesDefaultOf20() {
        when(transactionRepository.findByCustomerInRange(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of()));

        service.getByCustomerId(CUSTOMER, null, null, null, null, 0, "desc");

        verify(transactionRepository).findByCustomerInRange(
                CUSTOMER, null, null, null, null, PageRequest.of(0, 20));
    }

    // ── getById ──────────────────────────────────────────────────────────────

    @Test
    void getById_delegatesToRepository() {
        UUID id = UUID.randomUUID();
        Transaction tx = buildTransaction(id);
        when(transactionRepository.findByIdWithAssessment(id)).thenReturn(Optional.of(tx));

        Optional<Transaction> result = service.getById(id);

        assertThat(result).contains(tx);
    }

    @Test
    void getById_notFound_returnsEmpty() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findByIdWithAssessment(id)).thenReturn(Optional.empty());

        assertThat(service.getById(id)).isEmpty();
    }

    // ── getAssessment ─────────────────────────────────────────────────────────

    @Test
    void getAssessment_delegatesToRepository() {
        UUID id = UUID.randomUUID();
        FraudAssessment assessment = buildAssessment(buildTransaction(id), Disposition.CLEARED, 0);
        when(assessmentRepository.findByTransactionIdWithDetails(id)).thenReturn(Optional.of(assessment));

        Optional<FraudAssessment> result = service.getAssessment(id);

        assertThat(result).contains(assessment);
    }

    // ── getFlagged ────────────────────────────────────────────────────────────

    @Test
    void getFlagged_delegatesAllParametersToRepository() {
        FraudRule amountRule = mockRule("AmountThresholdRule");
        when(ruleManagementService.getRules()).thenReturn(List.of(amountRule));
        Slice<FraudAssessment> expected = new SliceImpl<>(List.of());
        when(assessmentRepository.findFlagged(
                eq(CUSTOMER), eq("AmountThresholdRule"), eq(50), eq(80),
                eq(FROM), eq(TO), eq(CURSOR_TS), eq(CURSOR_ID), any(PageRequest.class)))
                .thenReturn(expected);

        Slice<FraudAssessment> result = service.getFlagged(
                CUSTOMER, "AmountThresholdRule", 50, 80, FROM, TO, CURSOR_TS, CURSOR_ID, 15, "desc");

        assertThat(result).isSameAs(expected);
        verify(assessmentRepository).findFlagged(
                CUSTOMER, "AmountThresholdRule", 50, 80, FROM, TO, CURSOR_TS, CURSOR_ID, PageRequest.of(0, 15));
    }

    @Test
    void getFlagged_zeroPageSize_usesDefaultOf20() {
        when(assessmentRepository.findFlagged(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of()));

        service.getFlagged(null, null, null, null, null, null, null, null, 0, "desc");

        verify(assessmentRepository).findFlagged(
                null, null, null, null, null, null, null, null, PageRequest.of(0, 20));
    }

    @Test
    void getFlagged_unknownRuleViolated_throws400Message() {
        FraudRule amountRule = mockRule("AmountThresholdRule");
        when(ruleManagementService.getRules()).thenReturn(List.of(amountRule));

        assertThatThrownBy(() ->
                service.getFlagged(null, "TypoRule", null, null, null, null, null, null, 20, "desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TypoRule")
                .hasMessageContaining("AmountThresholdRule");
    }

    @Test
    void getFlaggedByMerchant_unknownRuleViolated_throws400Message() {
        FraudRule amountRule = mockRule("AmountThresholdRule");
        FraudRule velocityRule = mockRule("VelocityRule");
        when(ruleManagementService.getRules()).thenReturn(List.of(amountRule, velocityRule));

        assertThatThrownBy(() ->
                service.getFlaggedByMerchant(MERCHANT, "NoSuchRule", null, null, null, null, null, 20, "desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NoSuchRule");
    }

    // ── getPendingReview ──────────────────────────────────────────────────────

    @Test
    void getPendingReview_delegatesAllParametersToRepository() {
        FraudRule amountRule = mockRule("AmountThresholdRule");
        when(ruleManagementService.getRules()).thenReturn(List.of(amountRule));
        Slice<FraudAssessment> expected = new SliceImpl<>(List.of());
        when(assessmentRepository.findPendingReview(
                eq(CUSTOMER), eq("AmountThresholdRule"), eq(10), eq(49),
                eq(FROM), eq(TO), eq(CURSOR_TS), eq(CURSOR_ID), any(PageRequest.class)))
                .thenReturn(expected);

        Slice<FraudAssessment> result = service.getPendingReview(
                CUSTOMER, "AmountThresholdRule", 10, 49, FROM, TO, CURSOR_TS, CURSOR_ID, 15, "desc");

        assertThat(result).isSameAs(expected);
        verify(assessmentRepository).findPendingReview(
                CUSTOMER, "AmountThresholdRule", 10, 49, FROM, TO, CURSOR_TS, CURSOR_ID, PageRequest.of(0, 15));
    }

    @Test
    void getPendingReview_zeroPageSize_usesDefaultOf20() {
        when(assessmentRepository.findPendingReview(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of()));

        service.getPendingReview(null, null, null, null, null, null, null, null, 0, "desc");

        verify(assessmentRepository).findPendingReview(
                null, null, null, null, null, null, null, null, PageRequest.of(0, 20));
    }

    @Test
    void getPendingReview_unknownRuleViolated_throws400Message() {
        FraudRule amountRule = mockRule("AmountThresholdRule");
        when(ruleManagementService.getRules()).thenReturn(List.of(amountRule));

        assertThatThrownBy(() ->
                service.getPendingReview(null, "TypoRule", null, null, null, null, null, null, 20, "desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TypoRule")
                .hasMessageContaining("AmountThresholdRule");
    }

    // ── getPassed ─────────────────────────────────────────────────────────────

    @Test
    void getPassed_delegatesAllParametersToRepository() {
        Slice<FraudAssessment> expected = new SliceImpl<>(List.of());
        when(assessmentRepository.findPassed(
                eq(CUSTOMER), eq(30), eq(FROM), eq(TO), eq(CURSOR_TS), eq(CURSOR_ID), any(PageRequest.class)))
                .thenReturn(expected);

        Slice<FraudAssessment> result = service.getPassed(CUSTOMER, 30, FROM, TO, CURSOR_TS, CURSOR_ID, 5, "desc");

        assertThat(result).isSameAs(expected);
    }

    // ── getFlaggedByMerchant ──────────────────────────────────────────────────

    @Test
    void getFlaggedByMerchant_delegatesAllParametersToRepository() {
        FraudRule velocityRule = mockRule("VelocityRule");
        when(ruleManagementService.getRules()).thenReturn(List.of(velocityRule));
        Slice<FraudAssessment> expected = new SliceImpl<>(List.of());
        when(assessmentRepository.findFlaggedByMerchant(
                eq(MERCHANT), eq("VelocityRule"), eq(60), eq(FROM), eq(TO), eq(CURSOR_TS), eq(CURSOR_ID), any()))
                .thenReturn(expected);

        Slice<FraudAssessment> result = service.getFlaggedByMerchant(
                MERCHANT, "VelocityRule", 60, FROM, TO, CURSOR_TS, CURSOR_ID, 10, "desc");

        assertThat(result).isSameAs(expected);
    }

    // ── getFraudSummary ───────────────────────────────────────────────────────

    @Test
    void getFraudSummary_calculatesCorrectFraudRate() {
        when(assessmentRepository.countInRange(FROM, TO)).thenReturn(1000L);
        when(assessmentRepository.countFlaggedInRange(FROM, TO)).thenReturn(312L);
        when(assessmentRepository.countByRuleInRange(FROM, TO)).thenReturn(List.of());

        FraudSummaryDto dto = service.getFraudSummary(FROM, TO);

        // 312/1000 * 100 = 31.2, rounded to 2dp → 31.2
        assertThat(dto.getFraudRate()).isEqualTo(31.2);
    }

    @Test
    void getFraudSummary_fraudRateRoundedToTwoDecimalPlaces() {
        // 1/3 = 33.333...% → should round to 33.33
        when(assessmentRepository.countInRange(FROM, TO)).thenReturn(3L);
        when(assessmentRepository.countFlaggedInRange(FROM, TO)).thenReturn(1L);
        when(assessmentRepository.countByRuleInRange(FROM, TO)).thenReturn(List.of());

        FraudSummaryDto dto = service.getFraudSummary(FROM, TO);

        assertThat(dto.getFraudRate()).isEqualTo(33.33);
    }

    @Test
    void getFraudSummary_zeroAssessed_fraudRateIsZero() {
        when(assessmentRepository.countInRange(FROM, TO)).thenReturn(0L);
        when(assessmentRepository.countFlaggedInRange(FROM, TO)).thenReturn(0L);
        when(assessmentRepository.countByRuleInRange(FROM, TO)).thenReturn(List.of());

        FraudSummaryDto dto = service.getFraudSummary(FROM, TO);

        assertThat(dto.getFraudRate()).isZero();
    }

    @Test
    void getFraudSummary_totalNotFlaggedIsAssessedMinusFlagged() {
        when(assessmentRepository.countInRange(FROM, TO)).thenReturn(500L);
        when(assessmentRepository.countFlaggedInRange(FROM, TO)).thenReturn(20L);
        when(assessmentRepository.countByRuleInRange(FROM, TO)).thenReturn(List.of());

        FraudSummaryDto dto = service.getFraudSummary(FROM, TO);

        assertThat(dto.getTotalNotFlagged()).isEqualTo(480L);
        assertThat(dto.getTotalAssessed()).isEqualTo(500L);
        assertThat(dto.getTotalFlagged()).isEqualTo(20L);
    }

    @Test
    void getFraudSummary_setsFromAndToFields() {
        when(assessmentRepository.countInRange(FROM, TO)).thenReturn(0L);
        when(assessmentRepository.countFlaggedInRange(FROM, TO)).thenReturn(0L);
        when(assessmentRepository.countByRuleInRange(FROM, TO)).thenReturn(List.of());

        FraudSummaryDto dto = service.getFraudSummary(FROM, TO);

        assertThat(dto.getFrom()).isEqualTo(FROM);
        assertThat(dto.getTo()).isEqualTo(TO);
    }

    @Test
    void getFraudSummary_calculatesRuleBreakdownPercentages() {
        // 2 rules fired out of 4 total flagged
        Object[] row1 = { "AmountThresholdRule", 3L };
        Object[] row2 = { "VelocityRule",        1L };

        when(assessmentRepository.countInRange(FROM, TO)).thenReturn(10L);
        when(assessmentRepository.countFlaggedInRange(FROM, TO)).thenReturn(4L);
        when(assessmentRepository.countByRuleInRange(FROM, TO)).thenReturn(List.<Object[]>of(row1, row2));

        FraudSummaryDto dto = service.getFraudSummary(FROM, TO);

        assertThat(dto.getRuleBreakdown()).hasSize(2);
        assertThat(dto.getRuleBreakdown().get(0).getRuleName()).isEqualTo("AmountThresholdRule");
        assertThat(dto.getRuleBreakdown().get(0).getCount()).isEqualTo(3L);
        // 3/4 * 100 = 75.0
        assertThat(dto.getRuleBreakdown().get(0).getPercentage()).isEqualTo(75.0);
        assertThat(dto.getRuleBreakdown().get(1).getPercentage()).isEqualTo(25.0);
    }

    @Test
    void getFraudSummary_zeroFlagged_ruleBreakdownPercentageIsZero() {
        Object[] row = { "AmountThresholdRule", 0L };

        when(assessmentRepository.countInRange(FROM, TO)).thenReturn(5L);
        when(assessmentRepository.countFlaggedInRange(FROM, TO)).thenReturn(0L);
        when(assessmentRepository.countByRuleInRange(FROM, TO)).thenReturn(List.<Object[]>of(row));

        FraudSummaryDto dto = service.getFraudSummary(FROM, TO);

        assertThat(dto.getRuleBreakdown().get(0).getPercentage()).isZero();
    }

    @Test
    void getFraudSummary_emptyRuleBreakdown_returnsEmptyList() {
        when(assessmentRepository.countInRange(FROM, TO)).thenReturn(100L);
        when(assessmentRepository.countFlaggedInRange(FROM, TO)).thenReturn(0L);
        when(assessmentRepository.countByRuleInRange(FROM, TO)).thenReturn(List.of());

        FraudSummaryDto dto = service.getFraudSummary(FROM, TO);

        assertThat(dto.getRuleBreakdown()).isEmpty();
    }

    // ── getCustomerRiskSummary ────────────────────────────────────────────────

    @Test
    void getCustomerRiskSummary_calculatesCorrectFraudRate() {
        when(transactionRepository.countByCustomerId(CUSTOMER, null)).thenReturn(200L);
        when(assessmentRepository.countFlaggedByCustomerId(CUSTOMER, null)).thenReturn(10L);
        when(assessmentRepository.findMaxRiskScoreByCustomerId(CUSTOMER, null)).thenReturn(85);
        when(assessmentRepository.findTopRulesByCustomerId(eq(CUSTOMER), isNull(), any())).thenReturn(List.of());
        when(transactionRepository.findFirstTransactionTimestamp(CUSTOMER)).thenReturn(Optional.empty());
        when(transactionRepository.findLastTransactionTimestamp(CUSTOMER)).thenReturn(Optional.empty());

        CustomerRiskSummaryDto dto = service.getCustomerRiskSummary(CUSTOMER, null);

        // 10/200 * 100 = 5.0
        assertThat(dto.getFraudRate()).isEqualTo(5.0);
    }

    @Test
    void getCustomerRiskSummary_zeroTransactions_fraudRateIsZero() {
        when(transactionRepository.countByCustomerId(CUSTOMER, null)).thenReturn(0L);
        when(assessmentRepository.countFlaggedByCustomerId(CUSTOMER, null)).thenReturn(0L);
        when(assessmentRepository.findMaxRiskScoreByCustomerId(CUSTOMER, null)).thenReturn(null);
        when(assessmentRepository.findTopRulesByCustomerId(eq(CUSTOMER), isNull(), any())).thenReturn(List.of());
        when(transactionRepository.findFirstTransactionTimestamp(CUSTOMER)).thenReturn(Optional.empty());
        when(transactionRepository.findLastTransactionTimestamp(CUSTOMER)).thenReturn(Optional.empty());

        CustomerRiskSummaryDto dto = service.getCustomerRiskSummary(CUSTOMER, null);

        assertThat(dto.getFraudRate()).isZero();
        assertThat(dto.getTotalTransactions()).isZero();
        assertThat(dto.getFlaggedCount()).isZero();
        assertThat(dto.getNotFlaggedCount()).isZero();
    }

    @Test
    void getCustomerRiskSummary_nullMaxScore_defaultsToZero() {
        when(transactionRepository.countByCustomerId(CUSTOMER, null)).thenReturn(5L);
        when(assessmentRepository.countFlaggedByCustomerId(CUSTOMER, null)).thenReturn(0L);
        when(assessmentRepository.findMaxRiskScoreByCustomerId(CUSTOMER, null)).thenReturn(null);
        when(assessmentRepository.findTopRulesByCustomerId(eq(CUSTOMER), isNull(), any())).thenReturn(List.of());
        when(transactionRepository.findFirstTransactionTimestamp(CUSTOMER)).thenReturn(Optional.empty());
        when(transactionRepository.findLastTransactionTimestamp(CUSTOMER)).thenReturn(Optional.empty());

        CustomerRiskSummaryDto dto = service.getCustomerRiskSummary(CUSTOMER, null);

        assertThat(dto.getHighestRiskScore()).isZero();
    }

    @Test
    void getCustomerRiskSummary_populatesAllFields() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant last  = Instant.parse("2026-07-30T00:00:00Z");

        when(transactionRepository.countByCustomerId(CUSTOMER, FROM)).thenReturn(50L);
        when(assessmentRepository.countFlaggedByCustomerId(CUSTOMER, FROM)).thenReturn(2L);
        when(assessmentRepository.findMaxRiskScoreByCustomerId(CUSTOMER, FROM)).thenReturn(90);
        when(assessmentRepository.findTopRulesByCustomerId(eq(CUSTOMER), eq(FROM), any())).thenReturn(List.of());
        when(transactionRepository.findFirstTransactionTimestamp(CUSTOMER)).thenReturn(Optional.of(first));
        when(transactionRepository.findLastTransactionTimestamp(CUSTOMER)).thenReturn(Optional.of(last));

        CustomerRiskSummaryDto dto = service.getCustomerRiskSummary(CUSTOMER, FROM);

        assertThat(dto.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(dto.getTotalTransactions()).isEqualTo(50L);
        assertThat(dto.getFlaggedCount()).isEqualTo(2L);
        assertThat(dto.getNotFlaggedCount()).isEqualTo(48L);
        assertThat(dto.getHighestRiskScore()).isEqualTo(90);
        assertThat(dto.getFirstTransactionAt()).isEqualTo(first);
        assertThat(dto.getLastTransactionAt()).isEqualTo(last);
    }

    @Test
    void getCustomerRiskSummary_extractsTopRulesFromObjectArrayRows() {
        Object[] row1 = { "VelocityRule", 5L };
        Object[] row2 = { "AmountThresholdRule", 3L };
        Object[] row3 = { "GeographicAnomalyRule", 1L };

        when(transactionRepository.countByCustomerId(CUSTOMER, null)).thenReturn(100L);
        when(assessmentRepository.countFlaggedByCustomerId(CUSTOMER, null)).thenReturn(9L);
        when(assessmentRepository.findMaxRiskScoreByCustomerId(CUSTOMER, null)).thenReturn(75);
        when(assessmentRepository.findTopRulesByCustomerId(eq(CUSTOMER), isNull(), any()))
                .thenReturn(List.<Object[]>of(row1, row2, row3));
        when(transactionRepository.findFirstTransactionTimestamp(CUSTOMER)).thenReturn(Optional.empty());
        when(transactionRepository.findLastTransactionTimestamp(CUSTOMER)).thenReturn(Optional.empty());

        CustomerRiskSummaryDto dto = service.getCustomerRiskSummary(CUSTOMER, null);

        assertThat(dto.getMostTriggeredRules())
                .containsExactly("VelocityRule", "AmountThresholdRule", "GeographicAnomalyRule");
    }

    @Test
    void getCustomerRiskSummary_missingTimestamps_populatesNulls() {
        when(transactionRepository.countByCustomerId(CUSTOMER, null)).thenReturn(1L);
        when(assessmentRepository.countFlaggedByCustomerId(CUSTOMER, null)).thenReturn(0L);
        when(assessmentRepository.findMaxRiskScoreByCustomerId(CUSTOMER, null)).thenReturn(null);
        when(assessmentRepository.findTopRulesByCustomerId(eq(CUSTOMER), isNull(), any())).thenReturn(List.of());
        when(transactionRepository.findFirstTransactionTimestamp(CUSTOMER)).thenReturn(Optional.empty());
        when(transactionRepository.findLastTransactionTimestamp(CUSTOMER)).thenReturn(Optional.empty());

        CustomerRiskSummaryDto dto = service.getCustomerRiskSummary(CUSTOMER, null);

        assertThat(dto.getFirstTransactionAt()).isNull();
        assertThat(dto.getLastTransactionAt()).isNull();
    }

    // ── getMerchantRiskSummary ────────────────────────────────────────────────

    @Test
    void getMerchantRiskSummary_calculatesCorrectFraudRate() {
        when(transactionRepository.countByMerchantId(MERCHANT, null)).thenReturn(400L);
        when(assessmentRepository.countFlaggedByMerchantId(MERCHANT, null)).thenReturn(8L);
        when(assessmentRepository.findMaxRiskScoreByMerchantId(MERCHANT, null)).thenReturn(60);
        when(assessmentRepository.findTopRulesByMerchantId(eq(MERCHANT), isNull(), any())).thenReturn(List.of());
        when(transactionRepository.countDistinctCustomersByMerchantId(MERCHANT)).thenReturn(120L);
        when(transactionRepository.findFirstTransactionTimestampByMerchantId(MERCHANT)).thenReturn(Optional.empty());
        when(transactionRepository.findLastTransactionTimestampByMerchantId(MERCHANT)).thenReturn(Optional.empty());

        MerchantRiskSummaryDto dto = service.getMerchantRiskSummary(MERCHANT, null);

        // 8/400 * 100 = 2.0
        assertThat(dto.getFraudRate()).isEqualTo(2.0);
    }

    @Test
    void getMerchantRiskSummary_nullMaxScore_defaultsToZero() {
        when(transactionRepository.countByMerchantId(MERCHANT, null)).thenReturn(10L);
        when(assessmentRepository.countFlaggedByMerchantId(MERCHANT, null)).thenReturn(0L);
        when(assessmentRepository.findMaxRiskScoreByMerchantId(MERCHANT, null)).thenReturn(null);
        when(assessmentRepository.findTopRulesByMerchantId(eq(MERCHANT), isNull(), any())).thenReturn(List.of());
        when(transactionRepository.countDistinctCustomersByMerchantId(MERCHANT)).thenReturn(5L);
        when(transactionRepository.findFirstTransactionTimestampByMerchantId(MERCHANT)).thenReturn(Optional.empty());
        when(transactionRepository.findLastTransactionTimestampByMerchantId(MERCHANT)).thenReturn(Optional.empty());

        MerchantRiskSummaryDto dto = service.getMerchantRiskSummary(MERCHANT, null);

        assertThat(dto.getHighestRiskScore()).isZero();
    }

    @Test
    void getMerchantRiskSummary_populatesAllFields() {
        Instant first = Instant.parse("2026-03-01T00:00:00Z");
        Instant last  = Instant.parse("2026-07-30T00:00:00Z");

        when(transactionRepository.countByMerchantId(MERCHANT, FROM)).thenReturn(300L);
        when(assessmentRepository.countFlaggedByMerchantId(MERCHANT, FROM)).thenReturn(15L);
        when(assessmentRepository.findMaxRiskScoreByMerchantId(MERCHANT, FROM)).thenReturn(95);
        when(assessmentRepository.findTopRulesByMerchantId(eq(MERCHANT), eq(FROM), any())).thenReturn(List.of());
        when(transactionRepository.countDistinctCustomersByMerchantId(MERCHANT)).thenReturn(88L);
        when(transactionRepository.findFirstTransactionTimestampByMerchantId(MERCHANT)).thenReturn(Optional.of(first));
        when(transactionRepository.findLastTransactionTimestampByMerchantId(MERCHANT)).thenReturn(Optional.of(last));

        MerchantRiskSummaryDto dto = service.getMerchantRiskSummary(MERCHANT, FROM);

        assertThat(dto.getMerchantId()).isEqualTo(MERCHANT);
        assertThat(dto.getTotalTransactions()).isEqualTo(300L);
        assertThat(dto.getFlaggedCount()).isEqualTo(15L);
        assertThat(dto.getNotFlaggedCount()).isEqualTo(285L);
        assertThat(dto.getHighestRiskScore()).isEqualTo(95);
        assertThat(dto.getUniqueCustomers()).isEqualTo(88L);
        assertThat(dto.getFirstTransactionAt()).isEqualTo(first);
        assertThat(dto.getLastTransactionAt()).isEqualTo(last);
    }

    @Test
    void getMerchantRiskSummary_extractsTopRulesFromObjectArrayRows() {
        Object[] row1 = { "BlacklistedMerchantRule", 10L };
        Object[] row2 = { "AmountThresholdRule",     4L };

        when(transactionRepository.countByMerchantId(MERCHANT, null)).thenReturn(50L);
        when(assessmentRepository.countFlaggedByMerchantId(MERCHANT, null)).thenReturn(14L);
        when(assessmentRepository.findMaxRiskScoreByMerchantId(MERCHANT, null)).thenReturn(100);
        when(assessmentRepository.findTopRulesByMerchantId(eq(MERCHANT), isNull(), any()))
                .thenReturn(List.<Object[]>of(row1, row2));
        when(transactionRepository.countDistinctCustomersByMerchantId(MERCHANT)).thenReturn(20L);
        when(transactionRepository.findFirstTransactionTimestampByMerchantId(MERCHANT)).thenReturn(Optional.empty());
        when(transactionRepository.findLastTransactionTimestampByMerchantId(MERCHANT)).thenReturn(Optional.empty());

        MerchantRiskSummaryDto dto = service.getMerchantRiskSummary(MERCHANT, null);

        assertThat(dto.getMostTriggeredRules())
                .containsExactly("BlacklistedMerchantRule", "AmountThresholdRule");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Transaction buildTransaction(UUID id) {
        return Transaction.builder()
                .id(id)
                .customerId(CUSTOMER)
                .merchantId(MERCHANT)
                .amount(new BigDecimal("250.00"))
                .currency("ZAR")
                .transactionType(TransactionType.CARD_PRESENT)
                .timestamp(Instant.now())
                .status(TransactionStatus.ASSESSED)
                .build();
    }

    private FraudAssessment buildAssessment(Transaction tx, Disposition disposition, int score) {
        FraudAssessment a = FraudAssessment.builder()
                .transaction(tx)
                .disposition(disposition)
                .riskScore(score)
                .build();
        a.setRuleViolations(List.of());
        return a;
    }
}
