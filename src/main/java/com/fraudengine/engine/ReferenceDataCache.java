package com.fraudengine.engine;

import com.fraudengine.model.BlacklistedMerchant;
import com.fraudengine.model.MerchantLocation;
import com.fraudengine.repository.BlacklistedMerchantRepository;
import com.fraudengine.repository.MerchantLocationRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

// Cached reads of near-static reference data used during rule evaluation.
// Kept as a standalone bean rather than methods on EvaluationContextBuilder:
// Spring's @Cacheable proxy only intercepts calls that arrive from outside the
// bean, so a self-invoked call (build() calling a @Cacheable method on `this`)
// silently bypasses the cache. Routing through a separate collaborator bean
// means the call always goes through the proxy.
@Component
public class ReferenceDataCache {

    private final BlacklistedMerchantRepository blacklistedMerchantRepository;
    private final MerchantLocationRepository merchantLocationRepository;

    public ReferenceDataCache(BlacklistedMerchantRepository blacklistedMerchantRepository,
                              MerchantLocationRepository merchantLocationRepository) {
        this.blacklistedMerchantRepository = blacklistedMerchantRepository;
        this.merchantLocationRepository = merchantLocationRepository;
    }

    @Cacheable("blacklistedMerchants")
    public Set<String> getBlacklistedMerchantIds() {
        return blacklistedMerchantRepository.findAll().stream()
                .map(BlacklistedMerchant::getMerchantId)
                .collect(Collectors.toSet());
    }

    // Merchant locations change rarely (registered address), unlike the blacklist,
    // so this tolerates a much longer TTL — see CacheConfig.
    @Cacheable("merchantLocations")
    public Optional<MerchantLocation> getMerchantLocation(String merchantId) {
        return merchantLocationRepository.findById(merchantId);
    }
}
