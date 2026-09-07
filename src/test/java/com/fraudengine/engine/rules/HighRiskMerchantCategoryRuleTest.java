package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.engine.RuleResult;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HighRiskMerchantCategoryRuleTest {

    private HighRiskMerchantCategoryRule rule;
    private EvaluationContext emptyContext;

    @BeforeEach
    void setUp() {
        RuleProperties props = new RuleProperties();
        props.getHighRiskCategory().setEnabled(true);
        rule = new HighRiskMerchantCategoryRule(props);
        emptyContext = EvaluationContext.builder()
                .recentCustomerTransactions(List.of())
                .build();
    }

    // ---- HIGH-risk categories (score: 50) ----

    @Test
    void cryptoExchangeCategory_isHighViolation() {
        RuleResult result = rule.evaluate(tx("CRYPTO_EXCHANGE"), emptyContext);
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void cryptoKeyword_isHighViolation() {
        RuleResult result = rule.evaluate(tx("CRYPTO"), emptyContext);
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void moneyTransferCategory_isHighViolation() {
        RuleResult result = rule.evaluate(tx("MONEY_TRANSFER"), emptyContext);
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void wireTransferCategory_isHighViolation() {
        RuleResult result = rule.evaluate(tx("WIRE_TRANSFER"), emptyContext);
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void categoryContainingCryptoKeyword_isHighViolation() {
        // Case-insensitive partial match
        RuleResult result = rule.evaluate(tx("Online Crypto Trading"), emptyContext);
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
    }

    // ---- MEDIUM-risk categories (score: 25) ----

    @Test
    void gamblingCategory_isMediumViolation() {
        RuleResult result = rule.evaluate(tx("GAMBLING"), emptyContext);
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void casinoCategory_isMediumViolation() {
        RuleResult result = rule.evaluate(tx("CASINO"), emptyContext);
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void bettingCategory_isMediumViolation() {
        RuleResult result = rule.evaluate(tx("Sports BETTING"), emptyContext);
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void paydayLoanCategory_isMediumViolation() {
        RuleResult result = rule.evaluate(tx("PAYDAY_LOAN"), emptyContext);
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
    }

    // ---- HIGH wins over MEDIUM when category matches both ----

    @Test
    void highRiskKeywordTakesPriorityOverMedium() {
        // A category containing both a HIGH and a MEDIUM keyword should score HIGH
        RuleResult result = rule.evaluate(tx("CRYPTO GAMBLING"), emptyContext);
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
    }

    // ---- Safe categories pass ----

    @Test
    void retailCategory_passes() {
        assertThat(rule.evaluate(tx("RETAIL"), emptyContext).isViolation()).isFalse();
    }

    @Test
    void groceryCategory_passes() {
        assertThat(rule.evaluate(tx("GROCERY"), emptyContext).isViolation()).isFalse();
    }

    @Test
    void nullCategory_passes() {
        Transaction t = Transaction.builder()
                .id(UUID.randomUUID()).customerId("C").merchantId("M")
                .amount(new BigDecimal("100")).currency("ZAR")
                .timestamp(Instant.now()).build();
        assertThat(rule.evaluate(t, emptyContext).isViolation()).isFalse();
    }

    @Test
    void blankCategory_passes() {
        assertThat(rule.evaluate(tx("   "), emptyContext).isViolation()).isFalse();
    }

    @Test
    void disabledRule_isEnabled_returnsFalse() {
        RuleProperties props = new RuleProperties();
        props.getHighRiskCategory().setEnabled(false);
        assertThat(new HighRiskMerchantCategoryRule(props).isEnabled()).isFalse();
    }

    private Transaction tx(String category) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId("CUST_001").merchantId("MERCH_001")
                .amount(new BigDecimal("500.00")).currency("ZAR")
                .category(category).timestamp(Instant.now()).build();
    }
}
