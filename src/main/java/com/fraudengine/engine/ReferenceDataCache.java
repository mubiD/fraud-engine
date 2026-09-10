package com.fraudengine.engine;

import com.fraudengine.model.MerchantLocation;
import com.fraudengine.repository.MerchantLocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

// Cached reads of near-static reference data used during rule evaluation.
// Kept as a standalone bean rather than methods on EvaluationContextBuilder:
// Spring's @Cacheable proxy only intercepts calls that arrive from outside the
// bean, so a self-invoked call (build() calling a @Cacheable method on `this`)
// silently bypasses the cache. Routing through a separate collaborator bean
// means the call always goes through the proxy.
@Component
public class ReferenceDataCache {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataCache.class);
    private static final String MERCHANT_LOCATIONS_CACHE = "merchantLocations";

    private final MerchantLocationRepository merchantLocationRepository;
    private final CacheManager cacheManager;

    public ReferenceDataCache(MerchantLocationRepository merchantLocationRepository,
                              CacheManager cacheManager) {
        this.merchantLocationRepository = merchantLocationRepository;
        this.cacheManager = cacheManager;
    }

    // Merchant locations change rarely (registered address); see CacheConfig for TTL.
    @Cacheable(MERCHANT_LOCATIONS_CACHE)
    public Optional<MerchantLocation> getMerchantLocation(String merchantId) {
        return merchantLocationRepository.findById(merchantId);
    }

    // Eagerly loads every known merchant location into the cache. merchant_locations is
    // small, near-static reference data (low thousands of rows at most), so a full table
    // scan is cheap and turns "cache miss blocks on a DB read" into "cache miss only for a
    // merchant added since the last warm-up," a much narrower gap than the unbounded
    // per-lookup DB round trip this replaces. Runs at startup (initialDelay=0) and on the
    // configured interval thereafter, writing directly into the same Spring Cache instance
    // @Cacheable reads from, using the same key shape (merchantId -> Optional<MerchantLocation>)
    // a real lookup would have produced, so a warmed entry is indistinguishable from one
    // populated lazily.
    @Scheduled(
            initialDelay = 0,
            fixedRateString = "${fraud.cache.merchant-location.warmup-interval-minutes:30}",
            timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void warmMerchantLocationCache() {
        Cache cache = cacheManager.getCache(MERCHANT_LOCATIONS_CACHE);
        if (cache == null) {
            log.warn("'{}' cache not found — skipping warm-up", MERCHANT_LOCATIONS_CACHE);
            return;
        }

        List<MerchantLocation> locations = merchantLocationRepository.findAll();
        for (MerchantLocation location : locations) {
            cache.put(location.getMerchantId(), Optional.of(location));
        }
        log.info("Warmed '{}' cache: {} entries", MERCHANT_LOCATIONS_CACHE, locations.size());
    }
}
