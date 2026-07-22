package com.fraudengine.service;

import com.fraudengine.engine.FraudRule;
import com.fraudengine.engine.RuleEngine;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RuleManagementService {

    private final RuleEngine ruleEngine;

    public RuleManagementService(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    public List<FraudRule> getRules() {
        return ruleEngine.getRules();
    }
}
