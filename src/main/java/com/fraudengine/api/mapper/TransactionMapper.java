package com.fraudengine.api.mapper;

import com.fraudengine.api.dto.FraudAssessmentDto;
import com.fraudengine.api.dto.RuleViolationDto;
import com.fraudengine.api.dto.TransactionSummaryDto;
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
    @Mapping(target = "outcome", expression = "java(assessment.getOutcome().name())")
    @Mapping(target = "disposition", expression = "java(assessment.getDisposition().name())")
    FraudAssessmentDto toDto(FraudAssessment assessment);

    @Mapping(target = "severity", expression = "java(violation.getSeverity().name())")
    RuleViolationDto toDto(RuleViolation violation);

    @Mapping(target = "transactionId", source = "id")
    @Mapping(target = "assessment", source = "assessment")
    TransactionSummaryDto toSummaryDto(Transaction transaction);
}
