package com.fraudengine.api.dto;

import java.util.UUID;

public class SubmitTransactionResponse {

    private UUID transactionId;
    private String status;
    private String message;

    public SubmitTransactionResponse() {}

    public UUID getTransactionId() { return transactionId; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }

    public void setTransactionId(UUID v) { this.transactionId = v; }
    public void setStatus(String v) { this.status = v; }
    public void setMessage(String v) { this.message = v; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final SubmitTransactionResponse r = new SubmitTransactionResponse();
        public Builder transactionId(UUID v) { r.transactionId = v; return this; }
        public Builder status(String v) { r.status = v; return this; }
        public Builder message(String v) { r.message = v; return this; }
        public SubmitTransactionResponse build() { return r; }
    }
}
