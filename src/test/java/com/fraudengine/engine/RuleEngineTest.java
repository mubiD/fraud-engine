package com.fraudengine.engine;

import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleEngineTest {

    @Mock private FraudRule passingRule;
    @Mock private FraudRule violatingRule;
    @Mock private EvaluationContextBuilder contextBuilder;

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

        when(violatingRule.isEnabled()).thenReturn(true);
        when(violatingRule.getPriority()).thenReturn(2);
        when(violatingRule.evaluate(any(), any())).thenReturn(
                RuleResult.violation("VIOLATING", "1.0", "desc", Severity.HIGH));
    }

    @Test
    void noViolations_notFraudulent_scoreZero() {
        FraudAssessment result = new RuleEngine(List.of(passingRule), contextBuilder).evaluate(tx());
        assertThat(result.isFraudulent()).isFalse();
        assertThat(result.getRiskScore()).isZero();
    }

    @Test
    void highSeverityViolation_fraudulent() {
        FraudAssessment result = new RuleEngine(List.of(violatingRule), contextBuilder).evaluate(tx());
        assertThat(result.isFraudulent()).isTrue();
        assertThat(result.getRiskScore()).isEqualTo(50);
        assertThat(result.getRuleViolations()).hasSize(1);
    }

    @Test
    void disabledRule_skipped() {
        when(violatingRule.isEnabled()).thenReturn(false);
        FraudAssessment result = new RuleEngine(List.of(passingRule, violatingRule), contextBuilder).evaluate(tx());
        assertThat(result.isFraudulent()).isFalse();
    }

    @Test
    void riskScoreCappedAt100() {
        FraudRule c1 = criticalRule(1), c2 = criticalRule(2);
        FraudAssessment result = new RuleEngine(List.of(c1, c2), contextBuilder).evaluate(tx());
        assertThat(result.getRiskScore()).isEqualTo(100);
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
