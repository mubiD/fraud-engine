package com.fraudengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "fraud.rules")
public class RuleProperties {

    private int contextLookbackMinutes = 60;
    private AmountThresholdConfig amountThreshold = new AmountThresholdConfig();
    private VelocityConfig velocity = new VelocityConfig();
    private DuplicateConfig duplicate = new DuplicateConfig();
    private BlacklistedMerchantConfig blacklistedMerchant = new BlacklistedMerchantConfig();
    private GeographicConfig geographic = new GeographicConfig();

    public int getContextLookbackMinutes() { return contextLookbackMinutes; }
    public void setContextLookbackMinutes(int v) { this.contextLookbackMinutes = v; }
    public AmountThresholdConfig getAmountThreshold() { return amountThreshold; }
    public void setAmountThreshold(AmountThresholdConfig v) { this.amountThreshold = v; }
    public VelocityConfig getVelocity() { return velocity; }
    public void setVelocity(VelocityConfig v) { this.velocity = v; }
    public DuplicateConfig getDuplicate() { return duplicate; }
    public void setDuplicate(DuplicateConfig v) { this.duplicate = v; }
    public BlacklistedMerchantConfig getBlacklistedMerchant() { return blacklistedMerchant; }
    public void setBlacklistedMerchant(BlacklistedMerchantConfig v) { this.blacklistedMerchant = v; }
    public GeographicConfig getGeographic() { return geographic; }
    public void setGeographic(GeographicConfig v) { this.geographic = v; }

    public static class AmountThresholdConfig {
        private boolean enabled = true;
        private BigDecimal threshold = new BigDecimal("5000.00");
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public BigDecimal getThreshold() { return threshold; }
        public void setThreshold(BigDecimal v) { this.threshold = v; }
    }

    public static class VelocityConfig {
        private boolean enabled = true;
        private int maxTransactions = 5;
        private int windowMinutes = 10;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getMaxTransactions() { return maxTransactions; }
        public void setMaxTransactions(int v) { this.maxTransactions = v; }
        public int getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(int v) { this.windowMinutes = v; }
    }

    public static class DuplicateConfig {
        private boolean enabled = true;
        private int windowSeconds = 300;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int v) { this.windowSeconds = v; }
    }

    public static class BlacklistedMerchantConfig {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }

    public static class GeographicConfig {
        private boolean enabled = true;
        private int windowMinutes = 60;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(int v) { this.windowMinutes = v; }
    }
}
