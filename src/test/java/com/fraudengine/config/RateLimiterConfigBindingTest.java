package com.fraudengine.config;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.springboot3.ratelimiter.autoconfigure.RateLimiterAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// Loads the REAL application.yml through Spring Boot's own test bootstrapping (not re-typed
// literal values, which could themselves silently drift from the file) and asserts on the
// actual bound RateLimiterConfig — infrastructure-free (only resilience4j's rate-limiter
// autoconfiguration, no DB/Kafka/full app context), so this runs under plain `mvn test`, not
// gated behind Docker/-Pconfluent like the integration suite. Found live 2026-09-16: the
// "api" limiter shipped undersized (100/10s) and rejected ~48% of realistic load-test
// traffic; nothing caught it because nothing bound and asserted on these values at all, in
// either direction — a future accidental revert or typo in application.yml would ship the
// same way, invisible to `mvn test`.
@SpringBootTest(classes = RateLimiterConfigBindingTest.MinimalRateLimiterConfig.class)
class RateLimiterConfigBindingTest {

    @ImportAutoConfiguration(RateLimiterAutoConfiguration.class)
    @Configuration
    static class MinimalRateLimiterConfig {}

    @Autowired
    private RateLimiterRegistry registry;

    @Test
    void apiLimiter_bindsTo300RequestsPerOneSecondWindow() {
        RateLimiterConfig config = registry.rateLimiter("api").getRateLimiterConfig();
        assertThat(config.getLimitForPeriod()).isEqualTo(300);
        assertThat(config.getLimitRefreshPeriod()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void standaloneSubmitLimiter_bindsTo20RequestsPerTenSecondWindow() {
        RateLimiterConfig config = registry.rateLimiter("standalone-submit").getRateLimiterConfig();
        assertThat(config.getLimitForPeriod()).isEqualTo(20);
        assertThat(config.getLimitRefreshPeriod()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void standaloneStreamLimiter_bindsTo5RequestsPerTenSecondWindow() {
        RateLimiterConfig config = registry.rateLimiter("standalone-stream").getRateLimiterConfig();
        assertThat(config.getLimitForPeriod()).isEqualTo(5);
        assertThat(config.getLimitRefreshPeriod()).isEqualTo(Duration.ofSeconds(10));
    }
}
