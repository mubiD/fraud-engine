package com.fraudengine.engine;

import com.fraudengine.config.FraudMetrics;
import com.fraudengine.config.ScoringProperties;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Disposition;
import com.fraudengine.model.enums.Severity;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleEngineTest {

    @Mock private FraudRule passingRule;
    @Mock private FraudRule violatingRule;
    @Mock private EvaluationContextBuilder contextBuilder;
    @Mock private FraudMetrics fraudMetrics;

    // Real instance (not mocked) so these tests exercise the actual scoring
    // constants/fallbacks in ScoringProperties, not a stand-in for them.
    private final ScoringProperties scoringProperties = new ScoringProperties();

    private EvaluationContext emptyContext;

    @BeforeEach
    void setUp() {
        emptyContext = EvaluationContext.builder()
                .recentCustomerTransactions(List.of())
                .build();
        when(contextBuilder.build(any())).thenReturn(emptyContext);
        when(fraudMetrics.evaluationTimer()).thenReturn(
                Timer.builder("test.evaluation").register(new SimpleMeterRegistry()));

        when(passingRule.isEnabled()).thenReturn(true);
        when(passingRule.getPriority()).thenReturn(1);
        when(passingRule.evaluate(any(), any())).thenReturn(RuleResult.pass("PASSING"));

        // "VIOLATING" has no entry in ScoringProperties.likelihoodRatios, so every
        // test using it exercises the per-severity fallback path deliberately.
        when(violatingRule.isEnabled()).thenReturn(true);
        when(violatingRule.getPriority()).thenReturn(2);
        when(violatingRule.evaluate(any(), any())).thenReturn(
                RuleResult.violation("VIOLATING", "1.0", "desc", Severity.CRITICAL));
    }

    @Test
    void noViolations_cleared_scoreMatchesPrior() {
        FraudAssessment result = new RuleEngine(List.of(passingRule), contextBuilder, scoringProperties, fraudMetrics).evaluate(tx());
        assertThat(result.getDisposition()).isEqualTo(Disposition.CLEARED);
        // No evidence fired, so the score should sit near the assumed base rate
        // (ScoringProperties.priorFraudProbability, default 1%), not zero: a
        // clean transaction isn't proof of innocence, just the absence of signal.
        assertThat(result.getRiskScore()).isBetween(0, 5);
    }

    @Test
    void criticalSeverityViolation_fallsBackToSeverityDefault_isFlagged() {
        FraudAssessment result = new RuleEngine(List.of(violatingRule), contextBuilder, scoringProperties, fraudMetrics).evaluate(tx());
        assertThat(result.getDisposition()).isEqualTo(Disposition.FLAGGED);
        assertThat(result.getRiskScore()).isGreaterThanOrEqualTo(50);
        assertThat(result.getRuleViolations()).hasSize(1);
    }

    // ── metrics ──────────────────────────────────────────────────────────────
    // Recorded inside RuleEngine.evaluate() itself (not by callers) so every ingress
    // path (Kafka consumer, or the synchronous standalone/local demo stub) reports
    // the same fraud.assessments.total / fraud.rule.evaluation.duration.seconds.

    @Test
    void evaluate_cleared_recordsClearedCounterOnly() {
        new RuleEngine(List.of(passingRule), contextBuilder, scoringProperties, fraudMetrics).evaluate(tx());
        verify(fraudMetrics).recordCleared();
        verify(fraudMetrics, never()).recordFlagged();
        verify(fraudMetrics, never()).recordPendingReview();
    }

    @Test
    void evaluate_flagged_recordsFlaggedCounterOnly() {
        new RuleEngine(List.of(violatingRule), contextBuilder, scoringProperties, fraudMetrics).evaluate(tx());
        verify(fraudMetrics).recordFlagged();
        verify(fraudMetrics, never()).recordCleared();
        verify(fraudMetrics, never()).recordPendingReview();
    }

    @Test
    void evaluate_pendingReview_recordsPendingReviewCounterOnly() {
        FraudRule m1 = mediumRule(1), m2 = mediumRule(2), m3 = mediumRule(3);
        new RuleEngine(List.of(m1, m2, m3), contextBuilder, scoringProperties, fraudMetrics).evaluate(tx());
        verify(fraudMetrics).recordPendingReview();
        verify(fraudMetrics, never()).recordFlagged();
        verify(fraudMetrics, never()).recordCleared();
    }

    @Test
    void evaluate_alwaysRecordsEvaluationTimerLatency() {
        new RuleEngine(List.of(passingRule), contextBuilder, scoringProperties, fraudMetrics).evaluate(tx());
        verify(fraudMetrics, atLeastOnce()).evaluationTimer();
    }

    @Test
    void lowSeverityViolationAlone_cleared() {
        FraudRule lowRule = mock(FraudRule.class);
        when(lowRule.isEnabled()).thenReturn(true);
        when(lowRule.getPriority()).thenReturn(1);
        when(lowRule.evaluate(any(), any())).thenReturn(
                RuleResult.violation("SOME_LOW_SEVERITY_RULE", "1.0", "d", Severity.LOW));

        FraudAssessment result = new RuleEngine(List.of(lowRule), contextBuilder, scoringProperties, fraudMetrics).evaluate(tx());
        assertThat(result.getDisposition()).isEqualTo(Disposition.CLEARED);
    }

    @Test
    void threeWeakViolations_combinedCrossesReviewThreshold_pendingReview() {
        // Three independent MEDIUM-fallback violations (ratio 3.0 each) combine to
        // ~21% posterior probability, above reviewProbabilityThreshold (10%) but
        // well below fraudProbabilityThreshold (50%). Mirrors the effectiveness
        // suite's "combined weak signals" scenarios at the RuleEngine level.
        FraudRule m1 = mediumRule(1), m2 = mediumRule(2), m3 = mediumRule(3);
        FraudAssessment result = new RuleEngine(List.of(m1, m2, m3), contextBuilder, scoringProperties, fraudMetrics).evaluate(tx());
        assertThat(result.getDisposition()).isEqualTo(Disposition.PENDING_REVIEW);
    }

    @Test
    void disabledRule_skipped_cleared() {
        when(violatingRule.isEnabled()).thenReturn(false);
        FraudAssessment result = new RuleEngine(List.of(passingRule, violatingRule), contextBuilder, scoringProperties, fraudMetrics).evaluate(tx());
        assertThat(result.getDisposition()).isEqualTo(Disposition.CLEARED);
    }

    @Test
    void scoringProperties_reviewThresholdNotBelowFraudThreshold_throws() {
        ScoringProperties props = new ScoringProperties();
        props.setReviewProbabilityThreshold(0.6);
        props.setFraudProbabilityThreshold(0.5);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void moreCorroboratingViolations_neverLowersRiskScore() {
        FraudRule c1 = criticalRule(1), c2 = criticalRule(2);
        int oneViolationScore = new RuleEngine(List.of(c1), contextBuilder, scoringProperties, fraudMetrics)
                .evaluate(tx()).getRiskScore();
        int twoViolationScore = new RuleEngine(List.of(c1, c2), contextBuilder, scoringProperties, fraudMetrics)
                .evaluate(tx()).getRiskScore();

        assertThat(twoViolationScore).isGreaterThanOrEqualTo(oneViolationScore);
        assertThat(twoViolationScore).isLessThanOrEqualTo(100);
    }

    @Test
    void riskScoreNeverExceeds100() {
        List<FraudRule> manyRules = List.of(
                criticalRule(1), criticalRule(2), criticalRule(3), criticalRule(4));
        FraudAssessment result = new RuleEngine(manyRules, contextBuilder, scoringProperties, fraudMetrics).evaluate(tx());
        assertThat(result.getRiskScore()).isLessThanOrEqualTo(100);
    }

    private FraudRule criticalRule(int priority) {
        FraudRule r = mock(FraudRule.class);
        when(r.isEnabled()).thenReturn(true);
        when(r.getPriority()).thenReturn(priority);
        when(r.evaluate(any(), any())).thenReturn(RuleResult.violation("R" + priority, "1.0", "d", Severity.CRITICAL));
        return r;
    }

    private FraudRule mediumRule(int priority) {
        FraudRule r = mock(FraudRule.class);
        when(r.isEnabled()).thenReturn(true);
        when(r.getPriority()).thenReturn(priority);
        when(r.evaluate(any(), any())).thenReturn(RuleResult.violation("M" + priority, "1.0", "d", Severity.MEDIUM));
        return r;
    }

    private Transaction tx() {
        return Transaction.builder().id(UUID.randomUUID()).customerId("C").merchantId("M")
                .amount(BigDecimal.TEN).currency("GBP").timestamp(Instant.now()).build();
    }
}
