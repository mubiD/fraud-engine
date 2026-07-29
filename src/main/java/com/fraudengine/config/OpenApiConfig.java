package com.fraudengine.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "JWT issued by the Capitec IDP. Required for all /api/v1/** endpoints in non-local environments."
)
public class OpenApiConfig {

    @Bean
    public OpenAPI fraudEngineOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fraud Rule Engine API")
                        .description("Query API for fraud assessments, flagged/passed transactions, and active rule configuration.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Fraud Engineering")
                                .email("fraud-engineering@capitecbank.co.za")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
