package com.fraudengine.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        // Merchant locations are near-static reference data (registered address),
        // so a long TTL is safe.
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache("merchantLocations", Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build());

        return manager;
    }
}
