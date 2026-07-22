package com.fraudengine.api.dto;

import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.model.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class TransactionSummaryDto {

    private UUID transactionId;
    private String customerId;
    private String merchantId;
    private BigDecimal amount;
    private String currency;
    private String category;
    private TransactionType transactionType;
    private String location;
    private Double latitude;
    private Double longitude;
    private Instant timestamp;
    private TransactionStatus status;
    private FraudAssessmentDto assessment;

    public TransactionSummaryDto() {}

    public UUID getTransactionId() { return transactionId; }
    public String getCustomerId() { return customerId; }
    public String getMerchantId() { return merchantId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getCategory() { return category; }
    public TransactionType getTransactionType() { return transactionType; }
    public String getLocation() { return location; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Instant getTimestamp() { return timestamp; }
    public TransactionStatus getStatus() { return status; }
    public FraudAssessmentDto getAssessment() { return assessment; }

    public void setTransactionId(UUID v) { this.transactionId = v; }
    public void setCustomerId(String v) { this.customerId = v; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public void setCurrency(String v) { this.currency = v; }
    public void setCategory(String v) { this.category = v; }
    public void setTransactionType(TransactionType v) { this.transactionType = v; }
    public void setLocation(String v) { this.location = v; }
    public void setLatitude(Double v) { this.latitude = v; }
    public void setLongitude(Double v) { this.longitude = v; }
    public void setTimestamp(Instant v) { this.timestamp = v; }
    public void setStatus(TransactionStatus v) { this.status = v; }
    public void setAssessment(FraudAssessmentDto v) { this.assessment = v; }
}
