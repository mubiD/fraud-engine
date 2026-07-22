package com.fraudengine.api.controller;

import com.fraudengine.api.dto.RuleDto;
import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.service.RuleManagementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    private final RuleManagementService ruleManagementService;
    private final TransactionMapper mapper;

    public RuleController(RuleManagementService ruleManagementService, TransactionMapper mapper) {
        this.ruleManagementService = ruleManagementService;
        this.mapper = mapper;
    }

    @GetMapping
    public List<RuleDto> getRules() {
        return ruleManagementService.getRules().stream().map(mapper::toDto).toList();
    }
}
