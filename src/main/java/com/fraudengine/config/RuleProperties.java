package com.fraudengine.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "fraud.rules")
public class RuleProperties {

    private int contextLookbackMinutes = 60;
    private AmountThresholdConfig amountThreshold = new AmountThresholdConfig();
    private VelocityConfig velocity = new VelocityConfig();
    private DuplicateConfig duplicate = new DuplicateConfig();
    private GeographicConfig geographic = new GeographicConfig();
    private CardCloningConfig cardCloning = new CardCloningConfig();
    private TimeOfDayConfig timeOfDay = new TimeOfDayConfig();
    private HighRiskCategoryConfig highRiskCategory = new HighRiskCategoryConfig();
    private DeviceFingerprintConfig deviceFingerprint = new DeviceFingerprintConfig();
    private MultiChannelConfig multiChannel = new MultiChannelConfig();
    private CrossMerchantVelocityConfig crossMerchantVelocity = new CrossMerchantVelocityConfig();
    private CumulativeSpendingConfig cumulativeSpending = new CumulativeSpendingConfig();
    private CustomerAmountAnomalyConfig customerAmountAnomaly = new CustomerAmountAnomalyConfig();

    public int getContextLookbackMinutes() { return contextLookbackMinutes; }
    public void setContextLookbackMinutes(int v) { this.contextLookbackMinutes = v; }
    public AmountThresholdConfig getAmountThreshold() { return amountThreshold; }
    public void setAmountThreshold(AmountThresholdConfig v) { this.amountThreshold = v; }
    public VelocityConfig getVelocity() { return velocity; }
    public void setVelocity(VelocityConfig v) { this.velocity = v; }
    public DuplicateConfig getDuplicate() { return duplicate; }
    public void setDuplicate(DuplicateConfig v) { this.duplicate = v; }
    public GeographicConfig getGeographic() { return geographic; }
    public void setGeographic(GeographicConfig v) { this.geographic = v; }
    public CardCloningConfig getCardCloning() { return cardCloning; }
    public void setCardCloning(CardCloningConfig v) { this.cardCloning = v; }
    public TimeOfDayConfig getTimeOfDay() { return timeOfDay; }
    public void setTimeOfDay(TimeOfDayConfig v) { this.timeOfDay = v; }
    public HighRiskCategoryConfig getHighRiskCategory() { return highRiskCategory; }
    public void setHighRiskCategory(HighRiskCategoryConfig v) { this.highRiskCategory = v; }
    public DeviceFingerprintConfig getDeviceFingerprint() { return deviceFingerprint; }
    public void setDeviceFingerprint(DeviceFingerprintConfig v) { this.deviceFingerprint = v; }
    public MultiChannelConfig getMultiChannel() { return multiChannel; }
    public void setMultiChannel(MultiChannelConfig v) { this.multiChannel = v; }
    public CrossMerchantVelocityConfig getCrossMerchantVelocity() { return crossMerchantVelocity; }
    public void setCrossMerchantVelocity(CrossMerchantVelocityConfig v) { this.crossMerchantVelocity = v; }
    public CumulativeSpendingConfig getCumulativeSpending() { return cumulativeSpending; }
    public void setCumulativeSpending(CumulativeSpendingConfig v) { this.cumulativeSpending = v; }
    public CustomerAmountAnomalyConfig getCustomerAmountAnomaly() { return customerAmountAnomaly; }
    public void setCustomerAmountAnomaly(CustomerAmountAnomalyConfig v) { this.customerAmountAnomaly = v; }

    // Every config below whose window is measured against EvaluationContextBuilder's
    // recentCustomerTransactions list (i.e. bounded by contextLookbackMinutes) must be
    // listed here. Missing an entry means that rule silently under-counts once its
    // window is configured wider than the lookback, with no startup error to catch it —
    // add new rules' window fields to this map, not as a one-off if-check.
    //
    // customerAmountAnomaly.lookbackDays is deliberately NOT in this map: it's measured
    // in days (not minutes) against a separate, independently-fetched
    // customerBaselineTransactions list, not recentCustomerTransactions — there's no
    // contextLookbackMinutes relationship to cross-check.
    @PostConstruct
    public void validate() {
        Map<String, Integer> windowMinutesByRule = Map.of(
                "velocity", velocity.getWindowMinutes(),
                "geographic", geographic.getWindowMinutes(),
                "card-cloning", cardCloning.getWindowMinutes(),
                "device-fingerprint", deviceFingerprint.getWindowMinutes(),
                "multi-channel", multiChannel.getWindowMinutes(),
                "cross-merchant-velocity", crossMerchantVelocity.getWindowMinutes(),
                "cumulative-spending.hourly-window", cumulativeSpending.getHourlyWindowMinutes());

        windowMinutesByRule.forEach((name, minutes) -> {
            if (minutes > contextLookbackMinutes) {
                throw new IllegalStateException(String.format(
                        "fraud.rules.%s.window-minutes (%d) exceeds context-lookback-minutes (%d)",
                        name, minutes, contextLookbackMinutes));
            }
        });
    }

    public static class AmountThresholdConfig {
        private boolean enabled = true;
        private BigDecimal threshold = new BigDecimal("5000.00");
        private Map<String, BigDecimal> categoryThresholds = new java.util.HashMap<>();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public BigDecimal getThreshold() { return threshold; }
        public void setThreshold(BigDecimal v) { this.threshold = v; }
        public Map<String, BigDecimal> getCategoryThresholds() { return categoryThresholds; }
        public void setCategoryThresholds(Map<String, BigDecimal> v) { this.categoryThresholds = v; }

        public BigDecimal effectiveThreshold(String category) {
            if (category == null || category.isBlank()) return threshold;
            return categoryThresholds.getOrDefault(category.toUpperCase(), threshold);
        }
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
        private int cardPresentWindowSeconds = 120;
        private int cardNotPresentWindowSeconds = 300;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getCardPresentWindowSeconds() { return cardPresentWindowSeconds; }
        public void setCardPresentWindowSeconds(int v) { this.cardPresentWindowSeconds = v; }
        public int getCardNotPresentWindowSeconds() { return cardNotPresentWindowSeconds; }
        public void setCardNotPresentWindowSeconds(int v) { this.cardNotPresentWindowSeconds = v; }
    }

    public static class GeographicConfig {
        private boolean enabled = true;
        private int windowMinutes = 60;
        private double maxTravelSpeedKmh = 900.0;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(int v) { this.windowMinutes = v; }
        public double getMaxTravelSpeedKmh() { return maxTravelSpeedKmh; }
        public void setMaxTravelSpeedKmh(double v) { this.maxTravelSpeedKmh = v; }
    }

    public static class CardCloningConfig {
        private boolean enabled = true;
        private int windowMinutes = 10;
        private int minDifferentMerchants = 2;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(int v) { this.windowMinutes = v; }
        public int getMinDifferentMerchants() { return minDifferentMerchants; }
        public void setMinDifferentMerchants(int v) { this.minDifferentMerchants = v; }
    }

    public static class TimeOfDayConfig {
        private boolean enabled = true;
        private int offHoursStartHour = 23;
        private int offHoursEndHour = 5;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getOffHoursStartHour() { return offHoursStartHour; }
        public void setOffHoursStartHour(int v) { this.offHoursStartHour = v; }
        public int getOffHoursEndHour() { return offHoursEndHour; }
        public void setOffHoursEndHour(int v) { this.offHoursEndHour = v; }
    }

    public static class MultiChannelConfig {
        private boolean enabled = true;
        private int windowMinutes = 5;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(int v) { this.windowMinutes = v; }
    }

    public static class DeviceFingerprintConfig {
        private boolean enabled = true;
        private int windowMinutes = 60;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(int v) { this.windowMinutes = v; }
    }

    public static class CrossMerchantVelocityConfig {
        private boolean enabled = true;
        private int maxTransactions = 10;
        private int windowMinutes = 10;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getMaxTransactions() { return maxTransactions; }
        public void setMaxTransactions(int v) { this.maxTransactions = v; }
        public int getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(int v) { this.windowMinutes = v; }
    }

    public static class CumulativeSpendingConfig {
        private boolean enabled = true;
        private BigDecimal hourlyLimit = new BigDecimal("10000.00");
        private BigDecimal dailyLimit = new BigDecimal("25000.00");
        private int hourlyWindowMinutes = 60;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public BigDecimal getHourlyLimit() { return hourlyLimit; }
        public void setHourlyLimit(BigDecimal v) { this.hourlyLimit = v; }
        public BigDecimal getDailyLimit() { return dailyLimit; }
        public void setDailyLimit(BigDecimal v) { this.dailyLimit = v; }
        public int getHourlyWindowMinutes() { return hourlyWindowMinutes; }
        public void setHourlyWindowMinutes(int v) { this.hourlyWindowMinutes = v; }
    }

    public static class CustomerAmountAnomalyConfig {
        private boolean enabled = true;
        private int lookbackDays = 90;
        private int minHistoryCount = 5;
        private double stddevMultiplier = 3.0;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getLookbackDays() { return lookbackDays; }
        public void setLookbackDays(int v) { this.lookbackDays = v; }
        public int getMinHistoryCount() { return minHistoryCount; }
        public void setMinHistoryCount(int v) { this.minHistoryCount = v; }
        public double getStddevMultiplier() { return stddevMultiplier; }
        public void setStddevMultiplier(double v) { this.stddevMultiplier = v; }
    }

    public static class HighRiskCategoryConfig {
        private boolean enabled = true;
        private List<String> highRiskKeywords = List.of(
                "CRYPTO", "CRYPTOCURRENCY", "CRYPTO_EXCHANGE", "MONEY_TRANSFER", "WIRE_TRANSFER");
        private List<String> mediumRiskKeywords = List.of(
                "GAMBLING", "CASINO", "BETTING", "PAYDAY_LOAN");
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public List<String> getHighRiskKeywords() { return highRiskKeywords; }
        public void setHighRiskKeywords(List<String> v) { this.highRiskKeywords = v; }
        public List<String> getMediumRiskKeywords() { return mediumRiskKeywords; }
        public void setMediumRiskKeywords(List<String> v) { this.mediumRiskKeywords = v; }
    }
}
