package com.fraudengine.engine;

import com.fraudengine.model.Transaction;

import java.util.List;

public class EvaluationContext {

    private final List<Transaction> recentCustomerTransactions;
    // Resolved from merchant_locations for physical-channel transactions that carry no coordinates.
    // Null for CARD_NOT_PRESENT transactions. See EvaluationContextBuilder.
    private final Double merchantLatitude;
    private final Double merchantLongitude;
    // Pre-computed 24-hour spend total for the customer (excluding current transaction).
    private final java.math.BigDecimal dailySpendTotal;
    // Precomputed count/mean/stdDev of the customer's own historical amounts, used by
    // CustomerAmountAnomalyRule to compute a personal baseline z-score. Bound by
    // fraud.rules.customer-amount-anomaly.lookback-days (default 90), a materially longer
    // and differently-shaped window than recentCustomerTransactions (contextLookbackMinutes,
    // default 60 minutes). See EvaluationContextBuilder. AmountBaselineStats.empty() when
    // the rule is disabled.
    private final AmountBaselineStats customerAmountBaseline;

    private EvaluationContext(List<Transaction> recentCustomerTransactions,
                               Double merchantLatitude,
                               Double merchantLongitude,
                               java.math.BigDecimal dailySpendTotal,
                               AmountBaselineStats customerAmountBaseline) {
        this.recentCustomerTransactions = recentCustomerTransactions;
        this.merchantLatitude = merchantLatitude;
        this.merchantLongitude = merchantLongitude;
        this.dailySpendTotal = dailySpendTotal;
        this.customerAmountBaseline = customerAmountBaseline;
    }

    public List<Transaction> getRecentCustomerTransactions() { return recentCustomerTransactions; }
    public Double getMerchantLatitude()  { return merchantLatitude; }
    public Double getMerchantLongitude() { return merchantLongitude; }
    public java.math.BigDecimal getDailySpendTotal() { return dailySpendTotal; }
    public AmountBaselineStats getCustomerAmountBaseline() { return customerAmountBaseline; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private List<Transaction> recentCustomerTransactions;
        private Double merchantLatitude;
        private Double merchantLongitude;
        private java.math.BigDecimal dailySpendTotal = java.math.BigDecimal.ZERO;
        private AmountBaselineStats customerAmountBaseline = AmountBaselineStats.empty();

        public Builder recentCustomerTransactions(List<Transaction> v) {
            this.recentCustomerTransactions = v; return this;
        }
        public Builder merchantLatitude(Double v)   { this.merchantLatitude = v;  return this; }
        public Builder merchantLongitude(Double v)  { this.merchantLongitude = v; return this; }
        public Builder dailySpendTotal(java.math.BigDecimal v) { this.dailySpendTotal = v; return this; }
        public Builder customerAmountBaseline(AmountBaselineStats v) {
            this.customerAmountBaseline = v; return this;
        }
        public EvaluationContext build() {
            return new EvaluationContext(recentCustomerTransactions,
                    merchantLatitude, merchantLongitude, dailySpendTotal, customerAmountBaseline);
        }
    }
}
