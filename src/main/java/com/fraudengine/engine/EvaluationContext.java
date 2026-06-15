package com.fraudengine.engine;

import com.fraudengine.model.Transaction;

import java.util.List;
import java.util.Set;

public class EvaluationContext {

    private final List<Transaction> recentCustomerTransactions;
    private final Set<String> blacklistedMerchantIds;

    private EvaluationContext(List<Transaction> recentCustomerTransactions,
                               Set<String> blacklistedMerchantIds) {
        this.recentCustomerTransactions = recentCustomerTransactions;
        this.blacklistedMerchantIds = blacklistedMerchantIds;
    }

    public List<Transaction> getRecentCustomerTransactions() { return recentCustomerTransactions; }
    public Set<String> getBlacklistedMerchantIds() { return blacklistedMerchantIds; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private List<Transaction> recentCustomerTransactions;
        private Set<String> blacklistedMerchantIds;

        public Builder recentCustomerTransactions(List<Transaction> v) {
            this.recentCustomerTransactions = v; return this;
        }
        public Builder blacklistedMerchantIds(Set<String> v) {
            this.blacklistedMerchantIds = v; return this;
        }
        public EvaluationContext build() {
            return new EvaluationContext(recentCustomerTransactions, blacklistedMerchantIds);
        }
    }
}
