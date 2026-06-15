package com.fraudengine.api.dto;

import java.math.BigDecimal;

public class UpdateRuleRequest {

    private Boolean enabled;
    private BigDecimal threshold;
    private Integer maxTransactions;
    private Integer windowMinutes;
    private Integer windowSeconds;

    public UpdateRuleRequest() {}

    public Boolean getEnabled() { return enabled; }
    public BigDecimal getThreshold() { return threshold; }
    public Integer getMaxTransactions() { return maxTransactions; }
    public Integer getWindowMinutes() { return windowMinutes; }
    public Integer getWindowSeconds() { return windowSeconds; }

    public void setEnabled(Boolean v) { this.enabled = v; }
    public void setThreshold(BigDecimal v) { this.threshold = v; }
    public void setMaxTransactions(Integer v) { this.maxTransactions = v; }
    public void setWindowMinutes(Integer v) { this.windowMinutes = v; }
    public void setWindowSeconds(Integer v) { this.windowSeconds = v; }
}
