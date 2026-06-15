package com.fraudengine.service;

import com.fraudengine.api.dto.UpdateRuleRequest;
import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.engine.RuleEngine;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RuleManagementService {

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
                if (request.getEnabled() != null)
                    ruleProperties.getAmountThreshold().setEnabled(request.getEnabled());
                if (request.getThreshold() != null)
                    ruleProperties.getAmountThreshold().setThreshold(request.getThreshold());
            }
            case "VELOCITY" -> {
                if (request.getEnabled() != null)
                    ruleProperties.getVelocity().setEnabled(request.getEnabled());
                if (request.getMaxTransactions() != null)
                    ruleProperties.getVelocity().setMaxTransactions(request.getMaxTransactions());
                if (request.getWindowMinutes() != null)
                    ruleProperties.getVelocity().setWindowMinutes(request.getWindowMinutes());
            }
            case "DUPLICATE_TRANSACTION" -> {
                if (request.getEnabled() != null)
                    ruleProperties.getDuplicate().setEnabled(request.getEnabled());
                if (request.getWindowSeconds() != null)
                    ruleProperties.getDuplicate().setWindowSeconds(request.getWindowSeconds());
            }
            case "BLACKLISTED_MERCHANT" -> {
                if (request.getEnabled() != null)
                    ruleProperties.getBlacklistedMerchant().setEnabled(request.getEnabled());
            }
            case "GEOGRAPHIC_ANOMALY" -> {
                if (request.getEnabled() != null)
                    ruleProperties.getGeographic().setEnabled(request.getEnabled());
                if (request.getWindowMinutes() != null)
                    ruleProperties.getGeographic().setWindowMinutes(request.getWindowMinutes());
            }
            default -> throw new IllegalArgumentException("Unknown rule: " + ruleName);
        }
    }
}
