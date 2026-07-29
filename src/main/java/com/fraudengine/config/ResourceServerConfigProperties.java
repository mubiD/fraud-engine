package com.fraudengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "fraud.security")
@Getter
@Setter
public class ResourceServerConfigProperties {
    private List<String> allowedRoles = List.of();
    private List<String> whitelistedPaths = List.of();
    private List<String> protectedPaths = List.of();
}
