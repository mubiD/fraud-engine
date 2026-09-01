package com.fraudengine.engine;

import com.fraudengine.model.Transaction;

import java.util.List;
import java.util.Set;

public class EvaluationContext {

    private final List<Transaction> recentCustomerTransactions;
    private final Set<String> blacklistedMerchantIds;
    // Resolved from merchant_locations for physical-channel transactions that carry no coordinates.
    // Null for CARD_NOT_PRESENT transactions — see EvaluationContextBuilder.
    private final Double merchantLatitude;
    private final Double merchantLongitude;
    // Pre-computed 24-hour spend total for the customer (excluding current transaction).
    private final java.math.BigDecimal dailySpendTotal;
    // Longer-window (days, not minutes) transaction history for the same customer, used
    // by CustomerAmountAnomalyRule to compute a personal mean/stddev baseline. Separate
    // from recentCustomerTransactions, which is bound by contextLookbackMinutes and far
    // too short a window for behavioral baselining. Empty when the rule is disabled.
    private final List<Transaction> customerBaselineTransactions;

    private EvaluationContext(List<Transaction> recentCustomerTransactions,
                               Set<String> blacklistedMerchantIds,
                               Double merchantLatitude,
                               Double merchantLongitude,
                               java.math.BigDecimal dailySpendTotal,
                               List<Transaction> customerBaselineTransactions) {
        this.recentCustomerTransactions = recentCustomerTransactions;
        this.blacklistedMerchantIds = blacklistedMerchantIds;
        this.merchantLatitude = merchantLatitude;
        this.merchantLongitude = merchantLongitude;
        this.dailySpendTotal = dailySpendTotal;
        this.customerBaselineTransactions = customerBaselineTransactions;
    }

    public List<Transaction> getRecentCustomerTransactions() { return recentCustomerTransactions; }
    public Set<String> getBlacklistedMerchantIds() { return blacklistedMerchantIds; }
    public Double getMerchantLatitude()  { return merchantLatitude; }
    public Double getMerchantLongitude() { return merchantLongitude; }
    public java.math.BigDecimal getDailySpendTotal() { return dailySpendTotal; }
    public List<Transaction> getCustomerBaselineTransactions() { return customerBaselineTransactions; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private List<Transaction> recentCustomerTransactions;
        private Set<String> blacklistedMerchantIds;
        private Double merchantLatitude;
        private Double merchantLongitude;
        private java.math.BigDecimal dailySpendTotal = java.math.BigDecimal.ZERO;
        private List<Transaction> customerBaselineTransactions = List.of();

        public Builder recentCustomerTransactions(List<Transaction> v) {
            this.recentCustomerTransactions = v; return this;
        }
        public Builder blacklistedMerchantIds(Set<String> v) {
            this.blacklistedMerchantIds = v; return this;
        }
        public Builder merchantLatitude(Double v)   { this.merchantLatitude = v;  return this; }
        public Builder merchantLongitude(Double v)  { this.merchantLongitude = v; return this; }
        public Builder dailySpendTotal(java.math.BigDecimal v) { this.dailySpendTotal = v; return this; }
        public Builder customerBaselineTransactions(List<Transaction> v) {
            this.customerBaselineTransactions = v; return this;
        }
        public EvaluationContext build() {
            return new EvaluationContext(recentCustomerTransactions, blacklistedMerchantIds,
                    merchantLatitude, merchantLongitude, dailySpendTotal, customerBaselineTransactions);
        }
    }
}
