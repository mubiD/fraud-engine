package com.fraudengine.engine;

import com.fraudengine.config.ScoringProperties;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleEngineTest {

    @Mock private FraudRule passingRule;
    @Mock private FraudRule violatingRule;
    @Mock private EvaluationContextBuilder contextBuilder;

    // Real instance (not mocked) so these tests exercise the actual scoring
    // constants/fallbacks in ScoringProperties, not a stand-in for them.
    private final ScoringProperties scoringProperties = new ScoringProperties();

    private EvaluationContext emptyContext;

    @BeforeEach
    void setUp() {
        emptyContext = EvaluationContext.builder()
                .recentCustomerTransactions(List.of())
                .blacklistedMerchantIds(Set.of())
                .build();
        when(contextBuilder.build(any())).thenReturn(emptyContext);

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
    void noViolations_notFraudulent_scoreMatchesPrior() {
        FraudAssessment result = new RuleEngine(List.of(passingRule), contextBuilder, scoringProperties).evaluate(tx());
        assertThat(result.isFraudulent()).isFalse();
        // No evidence fired — the score should sit near the assumed base rate
        // (ScoringProperties.priorFraudProbability, default 1%), not zero: a
        // clean transaction isn't proof of innocence, just the absence of signal.
        assertThat(result.getRiskScore()).isBetween(0, 5);
    }

    @Test
    void criticalSeverityViolation_fallsBackToSeverityDefault_isFraudulent() {
        FraudAssessment result = new RuleEngine(List.of(violatingRule), contextBuilder, scoringProperties).evaluate(tx());
        assertThat(result.isFraudulent()).isTrue();
        assertThat(result.getRiskScore()).isGreaterThanOrEqualTo(50);
        assertThat(result.getRuleViolations()).hasSize(1);
    }

    @Test
    void lowSeverityViolationAlone_notFraudulent() {
        FraudRule lowRule = mock(FraudRule.class);
        when(lowRule.isEnabled()).thenReturn(true);
        when(lowRule.getPriority()).thenReturn(1);
        when(lowRule.evaluate(any(), any())).thenReturn(
                RuleResult.violation("SOME_LOW_SEVERITY_RULE", "1.0", "d", Severity.LOW));

        FraudAssessment result = new RuleEngine(List.of(lowRule), contextBuilder, scoringProperties).evaluate(tx());
        assertThat(result.isFraudulent()).isFalse();
    }

    @Test
    void disabledRule_skipped() {
        when(violatingRule.isEnabled()).thenReturn(false);
        FraudAssessment result = new RuleEngine(List.of(passingRule, violatingRule), contextBuilder, scoringProperties).evaluate(tx());
        assertThat(result.isFraudulent()).isFalse();
    }

    @Test
    void moreCorroboratingViolations_neverLowersRiskScore() {
        FraudRule c1 = criticalRule(1), c2 = criticalRule(2);
        int oneViolationScore = new RuleEngine(List.of(c1), contextBuilder, scoringProperties)
                .evaluate(tx()).getRiskScore();
        int twoViolationScore = new RuleEngine(List.of(c1, c2), contextBuilder, scoringProperties)
                .evaluate(tx()).getRiskScore();

        assertThat(twoViolationScore).isGreaterThanOrEqualTo(oneViolationScore);
        assertThat(twoViolationScore).isLessThanOrEqualTo(100);
    }

    @Test
    void riskScoreNeverExceeds100() {
        List<FraudRule> manyRules = List.of(
                criticalRule(1), criticalRule(2), criticalRule(3), criticalRule(4));
        FraudAssessment result = new RuleEngine(manyRules, contextBuilder, scoringProperties).evaluate(tx());
        assertThat(result.getRiskScore()).isLessThanOrEqualTo(100);
    }

    private FraudRule criticalRule(int priority) {
        FraudRule r = mock(FraudRule.class);
        when(r.isEnabled()).thenReturn(true);
        when(r.getPriority()).thenReturn(priority);
        when(r.evaluate(any(), any())).thenReturn(RuleResult.violation("R" + priority, "1.0", "d", Severity.CRITICAL));
        return r;
    }

    private Transaction tx() {
        return Transaction.builder().id(UUID.randomUUID()).customerId("C").merchantId("M")
                .amount(BigDecimal.TEN).currency("GBP").timestamp(Instant.now()).build();
    }
}
