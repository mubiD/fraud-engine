package com.fraudengine.streams;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fraudengine.model.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Lightweight projection of Transaction held inside CustomerActivityState — only the
// fields the recentCustomerTransactions-consuming rules actually read (VelocityRule,
// CrossMerchantVelocityRule, DuplicateTransactionRule, CardCloningRule,
// MultiChannelAnomalyRule, GeographicAnomalyRule). Deliberately excludes
// deviceFingerprint: transaction_event.proto (the production Kafka wire format) carries
// no such field, so DeviceFingerprintRule never sees one on this path either way.
public record RecentTransactionRecord(
        UUID id,
        String merchantId,
        BigDecimal amount,
        String currency,
        String category,
        TransactionType transactionType,
        Instant timestamp,
        Double latitude,
        Double longitude) {

    @JsonCreator
    public RecentTransactionRecord(
            @JsonProperty("id") UUID id,
            @JsonProperty("merchantId") String merchantId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("category") String category,
            @JsonProperty("transactionType") TransactionType transactionType,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("latitude") Double latitude,
            @JsonProperty("longitude") Double longitude) {
        this.id = id;
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency;
        this.category = category;
        this.transactionType = transactionType;
        this.timestamp = timestamp;
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
