package com.fraudengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fraud.security")
@Getter
@Setter
public class AuthenticationConfigProperties {
    private String idpBaseUri;
}
