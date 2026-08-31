package com.fraudengine.engine;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.model.MerchantLocation;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionType;
import com.fraudengine.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final ReferenceDataCache referenceDataCache;
    private final RuleProperties properties;

    public EvaluationContextBuilder(TransactionRepository transactionRepository,
                                    ReferenceDataCache referenceDataCache,
                                    RuleProperties properties) {
        this.transactionRepository = transactionRepository;
        this.referenceDataCache = referenceDataCache;
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

        java.math.BigDecimal dailySpend = transactionRepository.sumAmountByCustomerSince(
                transaction.getCustomerId(),
                transaction.getTimestamp().minus(24, ChronoUnit.HOURS));

        log.debug("Evaluation context built: recentTransactions={}, blacklistedMerchants={}, lookbackMinutes={}, dailySpend={}",
                recent.size(), blacklisted.size(), properties.getContextLookbackMinutes(), dailySpend);

        return EvaluationContext.builder()
                .recentCustomerTransactions(recent)
                .blacklistedMerchantIds(blacklisted)
                .merchantLatitude(merchantLat)
                .merchantLongitude(merchantLon)
                .dailySpendTotal(dailySpend)
                .build();
    }
}
