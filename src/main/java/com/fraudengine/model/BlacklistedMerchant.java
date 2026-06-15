package com.fraudengine.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "blacklisted_merchants")
public class BlacklistedMerchant {

    @Id
    @Column(name = "merchant_id")
    private String merchantId;

    private String reason;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt = Instant.now();

    public BlacklistedMerchant() {}

    public String getMerchantId() { return merchantId; }
    public String getReason() { return reason; }
    public Instant getAddedAt() { return addedAt; }

    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public void setReason(String reason) { this.reason = reason; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }
}
