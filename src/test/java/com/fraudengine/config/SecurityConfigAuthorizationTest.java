package com.fraudengine.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Closes a real gap: every controller test in this project runs under {local,test,standalone},
// which activates SecurityConfig.noSecurityFilterChain (permitAll everywhere). The production
// authorization rules in SecurityConfig.buildFilterChain (whitelist vs. protected-path role
// gate vs. default-authenticated) had zero test coverage anywhere before this class. It exercises
// the exact same production method (not a reimplementation) against a locally-supplied
// JwtDecoder + SecurityMockMvcRequestPostProcessors.jwt(), so it never needs a reachable IDP.
// SecurityConfig's real jwtDecoder() bean calls JwtDecoders.fromIssuerLocation, a genuine network
// call at bean-creation time, which is exactly why this test does not import SecurityConfig itself.
@WebMvcTest(controllers = SecurityConfigAuthorizationTest.ProbeController.class)
@Import({SecurityConfigAuthorizationTest.ProbeController.class, SecurityConfigAuthorizationTest.TestSecurityConfig.class})
class SecurityConfigAuthorizationTest {

    private static final String ROLE_ANALYST = "ROLE_FRAUD_ANALYST";
    private static final String ROLE_ENGINEER = "ROLE_FRAUD_ENGINEER";
    private static final String ROLE_UNRELATED = "ROLE_SOMETHING_ELSE";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void whitelistedPath_noAuth_permitted() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void protectedPath_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/probe")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedPath_authenticatedWithoutRequiredRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/probe").with(jwt().authorities(new SimpleGrantedAuthority(ROLE_UNRELATED))))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedPath_analystRole_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/probe").with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ANALYST))))
                .andExpect(status().isOk());
    }

    @Test
    void protectedPath_engineerRole_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/probe").with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ENGINEER))))
                .andExpect(status().isOk());
    }

    @Test
    void nonWhitelistedNonProtectedPath_noAuth_returns401() throws Exception {
        // Falls through to the default anyRequest().authenticated() rule.
        mockMvc.perform(get("/other")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonWhitelistedNonProtectedPath_anyAuthenticatedPrincipal_returns200() throws Exception {
        // anyRequest().authenticated() only requires authentication, not one of the
        // fraud.security.allowed-roles: narrower than /api/v1/**'s role gate.
        mockMvc.perform(get("/other").with(jwt().authorities(new SimpleGrantedAuthority(ROLE_UNRELATED))))
                .andExpect(status().isOk());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/actuator/health")
        public String health() { return "UP"; }

        @GetMapping("/api/v1/probe")
        public String probe() { return "ok"; }

        @GetMapping("/other")
        public String other() { return "ok"; }
    }

    @TestConfiguration
    @EnableWebSecurity
    static class TestSecurityConfig {

        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            ResourceServerConfigProperties resourceServerConfig = new ResourceServerConfigProperties();
            // Mirrors application.yml's real fraud.security.* values exactly.
            resourceServerConfig.setWhitelistedPaths(List.of("/actuator/health"));
            resourceServerConfig.setProtectedPaths(List.of("/api/v1/**"));
            resourceServerConfig.setAllowedRoles(List.of("FRAUD_ANALYST", "FRAUD_ENGINEER"));

            SecurityConfig prodSecurityConfig =
                    new SecurityConfig(new AuthenticationConfigProperties(), resourceServerConfig);

            // Never actually invoked: SecurityMockMvcRequestPostProcessors.jwt() injects an
            // authenticated principal directly, bypassing decode() entirely. But
            // oauth2ResourceServer().jwt().decoder(...) still needs some JwtDecoder instance
            // to build the filter chain.
            JwtDecoder unusedDecoder = token -> { throw new UnsupportedOperationException(); };

            return prodSecurityConfig.buildFilterChain(http, unusedDecoder);
        }
    }
}
