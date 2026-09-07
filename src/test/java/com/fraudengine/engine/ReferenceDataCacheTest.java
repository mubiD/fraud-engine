package com.fraudengine.engine;

import com.fraudengine.model.MerchantLocation;
import com.fraudengine.repository.MerchantLocationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReferenceDataCacheTest {

    @Mock private MerchantLocationRepository merchantLocationRepository;
    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;

    @Test
    void warmMerchantLocationCache_putsEveryRowUnderItsMerchantIdKey() {
        MerchantLocation m1 = location("M1");
        MerchantLocation m2 = location("M2");
        when(merchantLocationRepository.findAll()).thenReturn(List.of(m1, m2));
        when(cacheManager.getCache("merchantLocations")).thenReturn(cache);

        ReferenceDataCache referenceDataCache = new ReferenceDataCache(merchantLocationRepository, cacheManager);
        referenceDataCache.warmMerchantLocationCache();

        ArgumentCaptor<Object> keyCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(cache, times(2)).put(keyCaptor.capture(), valueCaptor.capture());

        assertThat(keyCaptor.getAllValues()).containsExactlyInAnyOrder("M1", "M2");
        assertThat(valueCaptor.getAllValues()).containsExactlyInAnyOrder(Optional.of(m1), Optional.of(m2));
    }

    @Test
    void warmMerchantLocationCache_cacheNotConfigured_doesNotThrow() {
        when(cacheManager.getCache("merchantLocations")).thenReturn(null);

        ReferenceDataCache referenceDataCache = new ReferenceDataCache(merchantLocationRepository, cacheManager);

        referenceDataCache.warmMerchantLocationCache();

        verify(merchantLocationRepository, never()).findAll();
    }

    @Test
    void warmMerchantLocationCache_noRows_putsNothing() {
        when(merchantLocationRepository.findAll()).thenReturn(List.of());
        when(cacheManager.getCache("merchantLocations")).thenReturn(cache);

        ReferenceDataCache referenceDataCache = new ReferenceDataCache(merchantLocationRepository, cacheManager);
        referenceDataCache.warmMerchantLocationCache();

        verify(cache, never()).put(any(), any());
    }

    private MerchantLocation location(String merchantId) {
        MerchantLocation location = new MerchantLocation();
        location.setMerchantId(merchantId);
        location.setLatitude(-33.9249);
        location.setLongitude(18.4241);
        return location;
    }
}
