package org.thingsboard.mqtt.broker.lightweight.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Caffeine cache configuration.
 *
 * <p>Verifies D-16: CaffeineCacheMetrics are explicitly registered via
 * {@link io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics#monitor(MeterRegistry, Object, String)}
 * and appear in the Micrometer registry.
 */
@SpringBootTest(properties = {
        "spring.config.name=tbmq-lightweight",
        "tbmq.storage.rocksdb.path=${java.io.tmpdir}/tbmq-test-cache",
        "tbmq.netty.port=0",
        "tbmq.ws.port=0",
        "tbmq.wss.enabled=false",
        "management.server.port=0"
})
@DirtiesContext
class CacheConfigurationTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void testCacheManager_isCaffeineCacheManager() {
        assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
    }

    @Test
    void testCacheNames_containsAllExpectedCaches() {
        Collection<String> cacheNames = cacheManager.getCacheNames();
        assertThat(cacheNames)
                .contains("credentials", "acl_rules", "sessions")
                .hasSize(3);
    }

    @Test
    void testCacheOperations_putAndGet() {
        Cache credentialsCache = cacheManager.getCache("credentials");
        assertThat(credentialsCache).isNotNull();

        credentialsCache.put("user1", "password-hash-abc");
        Cache.ValueWrapper result = credentialsCache.get("user1");

        assertThat(result).isNotNull();
        assertThat(result.get()).isEqualTo("password-hash-abc");
    }

    @Test
    void testCacheMetrics_registeredInMeterRegistry() {
        // Per D-16: CaffeineCacheMetrics.monitor() must be called for each cache,
        // resulting in "cache.gets" meters being registered in the MeterRegistry.
        // Trigger a cache access to ensure stats are populated.
        Cache credentialsCache = cacheManager.getCache("credentials");
        assertThat(credentialsCache).isNotNull();
        credentialsCache.get("nonexistent-key-for-metrics");

        // Verify that cache metrics are registered for the "credentials" cache
        var meters = meterRegistry.find("cache.gets")
                .tags("cache", "credentials")
                .meters();
        assertThat(meters)
                .as("CaffeineCacheMetrics should be registered for the 'credentials' cache (D-16 verification)")
                .isNotEmpty();
    }

}
