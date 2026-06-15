package com.fraudengine.service;

import com.fraudengine.api.dto.UpdateRuleRequest;
import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.engine.RuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RuleManagementService {

    private static final Logger log = LoggerFactory.getLogger(RuleManagementService.class);

    private final RuleEngine ruleEngine;
    private final RuleProperties ruleProperties;

    public RuleManagementService(RuleEngine ruleEngine, RuleProperties ruleProperties) {
        this.ruleEngine = ruleEngine;
        this.ruleProperties = ruleProperties;
    }

    public List<FraudRule> getRules() {
        return ruleEngine.getRules();
    }

    @CacheEvict(value = "blacklistedMerchants", allEntries = true)
    public void updateRule(String ruleName, UpdateRuleRequest request) {
        switch (ruleName.toUpperCase()) {
            case "AMOUNT_THRESHOLD" -> {
                if (request.getEnabled() != null) {
                    boolean prev = ruleProperties.getAmountThreshold().isEnabled();
                    ruleProperties.getAmountThreshold().setEnabled(request.getEnabled());
                    log.warn("Rule config changed: rule=AMOUNT_THRESHOLD, field=enabled, before={}, after={}",
                            prev, request.getEnabled());
                }
                if (request.getThreshold() != null) {
                    Object prev = ruleProperties.getAmountThreshold().getThreshold();
                    ruleProperties.getAmountThreshold().setThreshold(request.getThreshold());
                    log.warn("Rule config changed: rule=AMOUNT_THRESHOLD, field=threshold, before={}, after={}",
                            prev, request.getThreshold());
                }
            }
            case "VELOCITY" -> {
                if (request.getEnabled() != null) {
                    boolean prev = ruleProperties.getVelocity().isEnabled();
                    ruleProperties.getVelocity().setEnabled(request.getEnabled());
                    log.warn("Rule config changed: rule=VELOCITY, field=enabled, before={}, after={}",
                            prev, request.getEnabled());
                }
                if (request.getMaxTransactions() != null) {
                    int prev = ruleProperties.getVelocity().getMaxTransactions();
                    ruleProperties.getVelocity().setMaxTransactions(request.getMaxTransactions());
                    log.warn("Rule config changed: rule=VELOCITY, field=maxTransactions, before={}, after={}",
                            prev, request.getMaxTransactions());
                }
                if (request.getWindowMinutes() != null) {
                    int prev = ruleProperties.getVelocity().getWindowMinutes();
                    ruleProperties.getVelocity().setWindowMinutes(request.getWindowMinutes());
                    log.warn("Rule config changed: rule=VELOCITY, field=windowMinutes, before={}, after={}",
                            prev, request.getWindowMinutes());
                }
            }
            case "DUPLICATE_TRANSACTION" -> {
                if (request.getEnabled() != null) {
                    boolean prev = ruleProperties.getDuplicate().isEnabled();
                    ruleProperties.getDuplicate().setEnabled(request.getEnabled());
                    log.warn("Rule config changed: rule=DUPLICATE_TRANSACTION, field=enabled, before={}, after={}",
                            prev, request.getEnabled());
                }
                if (request.getWindowSeconds() != null) {
                    int prev = ruleProperties.getDuplicate().getWindowSeconds();
                    ruleProperties.getDuplicate().setWindowSeconds(request.getWindowSeconds());
                    log.warn("Rule config changed: rule=DUPLICATE_TRANSACTION, field=windowSeconds, before={}, after={}",
                            prev, request.getWindowSeconds());
                }
            }
            case "BLACKLISTED_MERCHANT" -> {
                if (request.getEnabled() != null) {
                    boolean prev = ruleProperties.getBlacklistedMerchant().isEnabled();
                    ruleProperties.getBlacklistedMerchant().setEnabled(request.getEnabled());
                    log.warn("Rule config changed: rule=BLACKLISTED_MERCHANT, field=enabled, before={}, after={}",
                            prev, request.getEnabled());
                }
            }
            case "GEOGRAPHIC_ANOMALY" -> {
                if (request.getEnabled() != null) {
                    boolean prev = ruleProperties.getGeographic().isEnabled();
                    ruleProperties.getGeographic().setEnabled(request.getEnabled());
                    log.warn("Rule config changed: rule=GEOGRAPHIC_ANOMALY, field=enabled, before={}, after={}",
                            prev, request.getEnabled());
                }
                if (request.getWindowMinutes() != null) {
                    int prev = ruleProperties.getGeographic().getWindowMinutes();
                    ruleProperties.getGeographic().setWindowMinutes(request.getWindowMinutes());
                    log.warn("Rule config changed: rule=GEOGRAPHIC_ANOMALY, field=windowMinutes, before={}, after={}",
                            prev, request.getWindowMinutes());
                }
            }
            default -> throw new IllegalArgumentException("Unknown rule: " + ruleName);
        }
    }
}
