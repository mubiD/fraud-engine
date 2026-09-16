package com.fraudengine.api.mapper;

import com.fraudengine.api.dto.RuleDto;
import com.fraudengine.engine.FraudRule;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RuleMapper {

    @Mapping(target = "ruleName", expression = "java(rule.getRuleName())")
    @Mapping(target = "ruleVersion", expression = "java(rule.getRuleVersion())")
    @Mapping(target = "priority", expression = "java(rule.getPriority())")
    @Mapping(target = "enabled", expression = "java(rule.isEnabled())")
    @Mapping(target = "config", expression = "java(rule.getConfig())")
    RuleDto toDto(FraudRule rule);
}
