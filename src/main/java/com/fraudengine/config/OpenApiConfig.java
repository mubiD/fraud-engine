package com.fraudengine.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
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
                                .email("fraud-engineering@capitecbank.co.za")));
    }
}
