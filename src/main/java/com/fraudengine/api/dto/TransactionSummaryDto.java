package com.fraudengine.api.dto;

import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.model.enums.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Summary view of a transaction, optionally including its fraud assessment")
public class TransactionSummaryDto {

    @Schema(description = "Unique transaction identifier", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID transactionId;

    @Schema(description = "Customer who initiated the transaction", example = "CUST-00123")
    private String customerId;

    @Schema(description = "Merchant at which the transaction occurred", example = "MERCH-NIKE-ZA")
    private String merchantId;

    @Schema(description = "Transaction amount", example = "1250.00")
    private BigDecimal amount;

    @Schema(description = "ISO 4217 currency code", example = "ZAR")
    private String currency;

    @Schema(description = "Merchant category", example = "RETAIL")
    private String category;

    @Schema(description = "Channel through which the transaction was made")
    private TransactionType transactionType;

    @Schema(description = "Human-readable location string", example = "Cape Town, ZA")
    private String location;

    @Schema(description = "Latitude of the transaction location", example = "-33.9249")
    private Double latitude;

    @Schema(description = "Longitude of the transaction location", example = "18.4241")
    private Double longitude;

    @Schema(description = "ISO-8601 UTC timestamp of when the transaction occurred", example = "2026-07-23T09:15:00Z")
    private Instant timestamp;

    @Schema(description = "Current processing status of the transaction")
    private TransactionStatus status;

    @Schema(description = "Fraud assessment result, present once the rule engine has processed the transaction")
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
