package com.fraudengine.api.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public class SubmitTransactionRequest {

    @NotBlank(message = "customerId is required")
    private String customerId;

    @NotBlank(message = "merchantId is required")
    private String merchantId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be positive")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
    private String currency;

    private String category;
    private String location;

    @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
    private Double latitude;

    @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
    private Double longitude;

    private Instant timestamp;

    public SubmitTransactionRequest() {}

    public String getCustomerId() { return customerId; }
    public String getMerchantId() { return merchantId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getCategory() { return category; }
    public String getLocation() { return location; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Instant getTimestamp() { return timestamp; }

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
        private final SubmitTransactionRequest r = new SubmitTransactionRequest();
        public Builder customerId(String v) { r.customerId = v; return this; }
        public Builder merchantId(String v) { r.merchantId = v; return this; }
        public Builder amount(BigDecimal v) { r.amount = v; return this; }
        public Builder currency(String v) { r.currency = v; return this; }
        public Builder category(String v) { r.category = v; return this; }
        public Builder location(String v) { r.location = v; return this; }
        public Builder latitude(Double v) { r.latitude = v; return this; }
        public Builder longitude(Double v) { r.longitude = v; return this; }
        public Builder timestamp(Instant v) { r.timestamp = v; return this; }
        public SubmitTransactionRequest build() { return r; }
    }
}
