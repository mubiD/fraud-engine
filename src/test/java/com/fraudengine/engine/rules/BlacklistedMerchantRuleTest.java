package com.fraudengine.engine.rules;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.EvaluationContext;
import com.fraudengine.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BlacklistedMerchantRuleTest {

    private BlacklistedMerchantRule rule;

    @BeforeEach
    void setUp() {
        rule = new BlacklistedMerchantRule(new RuleProperties());
    }

    @Test
    void cleanMerchant_passes() {
        assertThat(rule.evaluate(tx("CLEAN"), ctx(Set.of("BAD"))).isViolation()).isFalse();
    }

    @Test
    void blacklistedMerchant_isViolation() {
        var result = rule.evaluate(tx("BAD"), ctx(Set.of("BAD")));
        assertThat(result.isViolation()).isTrue();
        assertThat(result.getRuleName()).isEqualTo("BLACKLISTED_MERCHANT");
    }

    @Test
    void emptyBlacklist_passes() {
        assertThat(rule.evaluate(tx("ANY"), ctx(Set.of())).isViolation()).isFalse();
    }

    private Transaction tx(String merchantId) {
        return Transaction.builder().id(UUID.randomUUID()).customerId("C").merchantId(merchantId)
                .amount(BigDecimal.TEN).currency("GBP").timestamp(Instant.now()).build();
    }

    private EvaluationContext ctx(Set<String> blacklisted) {
        return EvaluationContext.builder().recentCustomerTransactions(List.of()).blacklistedMerchantIds(blacklisted).build();
    }
}
