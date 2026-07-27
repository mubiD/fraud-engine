package com.fraudengine.model;

import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.model.enums.TransactionType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@IdClass(TransactionId.class)
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    private String category;
    private String location;
    private Double latitude;
    private Double longitude;

    @Column(name = "device_fingerprint", length = 128)
    private String deviceFingerprint;

    @Id
    @Column(nullable = false)
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType = TransactionType.CARD_NOT_PRESENT;

    @OneToOne(mappedBy = "transaction", fetch = FetchType.LAZY)
    private FraudAssessment assessment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Transaction() {}

    // getters
    public UUID getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getMerchantId() { return merchantId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getCategory() { return category; }
    public String getLocation() { return location; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getDeviceFingerprint() { return deviceFingerprint; }
    public Instant getTimestamp() { return timestamp; }
    public TransactionType getTransactionType() { return transactionType; }
    public FraudAssessment getAssessment() { return assessment; }
    public TransactionStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    // setters
    public void setId(UUID id) { this.id = id; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setCategory(String category) { this.category = category; }
    public void setLocation(String location) { this.location = location; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public void setDeviceFingerprint(String deviceFingerprint) { this.deviceFingerprint = deviceFingerprint; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public void setTransactionType(TransactionType transactionType) { this.transactionType = transactionType; }
    public void setStatus(TransactionStatus status) { this.status = status; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final Transaction t = new Transaction();
        public Builder id(UUID id) { t.id = id; return this; }
        public Builder customerId(String v) { t.customerId = v; return this; }
        public Builder merchantId(String v) { t.merchantId = v; return this; }
        public Builder amount(BigDecimal v) { t.amount = v; return this; }
        public Builder currency(String v) { t.currency = v; return this; }
        public Builder category(String v) { t.category = v; return this; }
        public Builder location(String v) { t.location = v; return this; }
        public Builder latitude(Double v) { t.latitude = v; return this; }
        public Builder longitude(Double v) { t.longitude = v; return this; }
        public Builder deviceFingerprint(String v) { t.deviceFingerprint = v; return this; }
        public Builder timestamp(Instant v) { t.timestamp = v; return this; }
        public Builder transactionType(TransactionType v) { t.transactionType = v; return this; }
        public Builder status(TransactionStatus v) { t.status = v; return this; }
        public Transaction build() { return t; }
    }
}
