package com.fraudengine.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({AuthenticationConfigProperties.class, ResourceServerConfigProperties.class})
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final AuthenticationConfigProperties authenticationConfig;
    private final ResourceServerConfigProperties resourceServerConfig;

    @Bean
    @Profile("!local & !test & !standalone")
    public JwtDecoder jwtDecoder() {
        JwtDecoder defaultDecoder =
                JwtDecoders.fromIssuerLocation(authenticationConfig.getIdpBaseUri());
        return token -> {
            try {
                Jwt decodedJwt = defaultDecoder.decode(token);
                log.debug("Decoded JWT: {}", decodedJwt);
                return decodedJwt;
            } catch (JwtException ex) {
                log.error("Error decoding JWT: {}", ex.getMessage());
                throw ex;
            }
        };
    }

    @Bean
    @Profile("!local & !test & !standalone")
    SecurityFilterChain filterChain(
            final HttpSecurity http,
            final JwtDecoder jwtDecoder)
            throws Exception {

        String[] allowedRoles =
                resourceServerConfig.getAllowedRoles().stream()
                        .map(role -> "ROLE_" + role)
                        .toArray(String[]::new);

        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth ->
                        auth.requestMatchers(resourceServerConfig.getWhitelistedPaths().toArray(String[]::new))
                                .permitAll()
                                .requestMatchers(
                                        AntPathRequestMatcher.antMatcher("/swagger-ui/**"),
                                        AntPathRequestMatcher.antMatcher("/swagger-ui.html"),
                                        AntPathRequestMatcher.antMatcher("/v3/api-docs"),
                                        AntPathRequestMatcher.antMatcher("/v3/api-docs/**"))
                                .permitAll()
                                .requestMatchers(resourceServerConfig.getProtectedPaths().toArray(String[]::new))
                                .hasAnyAuthority(allowedRoles)
                                .anyRequest()
                                .authenticated())
                .oauth2ResourceServer(oauth ->
                        oauth.jwt(jwt ->
                                jwt.decoder(jwtDecoder)
                                        .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    @Bean
    @Profile("!local & !test & !standalone")
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter =
                new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();

        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");
        grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        jwtAuthenticationConverter.setPrincipalClaimName("sub");
        return jwtAuthenticationConverter;
    }

    @Bean
    @Profile({"local", "test", "standalone"})
    public SecurityFilterChain noSecurityFilterChain(final HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(req -> req.anyRequest().permitAll());
        return http.build();
    }
}
