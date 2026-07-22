package com.fraudengine.kafka;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fraudengine.model.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class ClearedTransactionEvent {

    private UUID transactionId;
    private String customerId;
    private String merchantId;
    private String amount;
    private String currency;
    private TransactionType transactionType;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;

    public ClearedTransactionEvent() {}

    public UUID getTransactionId() { return transactionId; }
    public String getCustomerId() { return customerId; }
    public String getMerchantId() { return merchantId; }
    public String getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public TransactionType getTransactionType() { return transactionType; }
    public Instant getTimestamp() { return timestamp; }

    public void setTransactionId(UUID v) { this.transactionId = v; }
    public void setCustomerId(String v) { this.customerId = v; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public void setAmount(String v) { this.amount = v; }
    public void setCurrency(String v) { this.currency = v; }
    public void setTransactionType(TransactionType v) { this.transactionType = v; }
    public void setTimestamp(Instant v) { this.timestamp = v; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final ClearedTransactionEvent e = new ClearedTransactionEvent();
        public Builder transactionId(UUID v) { e.transactionId = v; return this; }
        public Builder customerId(String v) { e.customerId = v; return this; }
        public Builder merchantId(String v) { e.merchantId = v; return this; }
        public Builder amount(BigDecimal v) { e.amount = v.toPlainString(); return this; }
        public Builder currency(String v) { e.currency = v; return this; }
        public Builder transactionType(TransactionType v) { e.transactionType = v; return this; }
        public Builder timestamp(Instant v) { e.timestamp = v; return this; }
        public ClearedTransactionEvent build() { return e; }
    }
}
