package com.fraudengine.api.mapper;

import com.fraudengine.api.dto.FraudAssessmentDto;
import com.fraudengine.api.dto.RuleDto;
import com.fraudengine.api.dto.RuleViolationDto;
import com.fraudengine.api.dto.TransactionSummaryDto;
import com.fraudengine.engine.FraudRule;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.RuleViolation;
import com.fraudengine.model.Transaction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TransactionMapper {

    @Mapping(target = "assessmentId", source = "id")
    @Mapping(target = "transactionId", source = "transaction.id")
    @Mapping(target = "violations", source = "ruleViolations")
    FraudAssessmentDto toDto(FraudAssessment assessment);

    @Mapping(target = "severity", expression = "java(violation.getSeverity().name())")
    RuleViolationDto toDto(RuleViolation violation);

    @Mapping(target = "ruleName", expression = "java(rule.getRuleName())")
    @Mapping(target = "ruleVersion", expression = "java(rule.getRuleVersion())")
    @Mapping(target = "priority", expression = "java(rule.getPriority())")
    @Mapping(target = "enabled", expression = "java(rule.isEnabled())")
    RuleDto toDto(FraudRule rule);

    @Mapping(target = "transactionId", source = "id")
    @Mapping(target = "assessment", source = "assessment")
    TransactionSummaryDto toSummaryDto(Transaction transaction);
}
