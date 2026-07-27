package com.fraudengine.api.controller;

import com.fraudengine.api.dto.RuleDto;
import com.fraudengine.api.mapper.TransactionMapper;
import com.fraudengine.service.RuleManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rules")
@Tag(name = "Rules", description = "Inspect the active fraud rule configuration")
public class RuleController {

    private final RuleManagementService ruleManagementService;
    private final TransactionMapper mapper;

    public RuleController(RuleManagementService ruleManagementService, TransactionMapper mapper) {
        this.ruleManagementService = ruleManagementService;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(
        summary = "List all fraud rules",
        description = "Returns each registered fraud rule with its name, version, priority, and whether it is currently enabled."
    )
    @ApiResponse(responseCode = "200", description = "Rules returned")
    public List<RuleDto> getRules() {
        return ruleManagementService.getRules().stream().map(mapper::toDto).toList();
    }
}
