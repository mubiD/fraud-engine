package com.fraudengine.kafka;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class TransactionEvent {

    private UUID transactionId;
    private String customerId;
    private String merchantId;
    private BigDecimal amount;
    private String currency;
    private String category;
    private String location;
    private Double latitude;
    private Double longitude;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;

    public TransactionEvent() {}

    public UUID getTransactionId() { return transactionId; }
    public String getCustomerId() { return customerId; }
    public String getMerchantId() { return merchantId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getCategory() { return category; }
    public String getLocation() { return location; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Instant getTimestamp() { return timestamp; }

    public void setTransactionId(UUID v) { this.transactionId = v; }
    public void setCustomerId(String v) { this.customerId = v; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public void setCurrency(String v) { this.currency = v; }
    public void setCategory(String v) { this.category = v; }
    public void setLocation(String v) { this.location = v; }
    public void setLatitude(Double v) { this.latitude = v; }
    public void setLongitude(Double v) { this.longitude = v; }
    public void setTimestamp(Instant v) { this.timestamp = v; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final TransactionEvent e = new TransactionEvent();
        public Builder transactionId(UUID v) { e.transactionId = v; return this; }
        public Builder customerId(String v) { e.customerId = v; return this; }
        public Builder merchantId(String v) { e.merchantId = v; return this; }
        public Builder amount(BigDecimal v) { e.amount = v; return this; }
        public Builder currency(String v) { e.currency = v; return this; }
        public Builder category(String v) { e.category = v; return this; }
        public Builder location(String v) { e.location = v; return this; }
        public Builder latitude(Double v) { e.latitude = v; return this; }
        public Builder longitude(Double v) { e.longitude = v; return this; }
        public Builder timestamp(Instant v) { e.timestamp = v; return this; }
        public TransactionEvent build() { return e; }
    }
}
