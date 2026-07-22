package com.fraudengine.engine;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.model.BlacklistedMerchant;
import com.fraudengine.model.Transaction;
import com.fraudengine.repository.BlacklistedMerchantRepository;
import com.fraudengine.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class EvaluationContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(EvaluationContextBuilder.class);

    private final TransactionRepository transactionRepository;
    private final BlacklistedMerchantRepository blacklistedMerchantRepository;
    private final RuleProperties properties;

    public EvaluationContextBuilder(TransactionRepository transactionRepository,
                                    BlacklistedMerchantRepository blacklistedMerchantRepository,
                                    RuleProperties properties) {
        this.transactionRepository = transactionRepository;
        this.blacklistedMerchantRepository = blacklistedMerchantRepository;
        this.properties = properties;
    }

    public EvaluationContext build(Transaction transaction) {
        Instant lookbackStart = transaction.getTimestamp()
                .minus(properties.getContextLookbackMinutes(), ChronoUnit.MINUTES);

        List<Transaction> recent = transactionRepository
                .findRecentByCustomer(transaction.getCustomerId(), lookbackStart)
                .stream()
                .filter(t -> !t.getId().equals(transaction.getId()))
                .collect(Collectors.toList());

        Set<String> blacklisted = getBlacklistedMerchantIds();

        log.debug("Evaluation context built: recentTransactions={}, blacklistedMerchants={}, lookbackMinutes={}",
                recent.size(), blacklisted.size(), properties.getContextLookbackMinutes());

        return EvaluationContext.builder()
                .recentCustomerTransactions(recent)
                .blacklistedMerchantIds(blacklisted)
                .build();
    }

    @Cacheable("blacklistedMerchants")
    public Set<String> getBlacklistedMerchantIds() {
        return blacklistedMerchantRepository.findAll().stream()
                .map(BlacklistedMerchant::getMerchantId)
                .collect(Collectors.toSet());
    }
}
