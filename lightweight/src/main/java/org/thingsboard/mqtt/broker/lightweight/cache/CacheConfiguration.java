package org.thingsboard.mqtt.broker.lightweight.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Caffeine cache configuration with explicit Micrometer metrics registration.
 *
 * <p>Per decision D-16: Caffeine cache metrics MUST be exposed via Micrometer's
 * {@link CaffeineCacheMetrics}. Spring Boot's {@code CacheMetricsAutoConfiguration}
 * calls {@link CaffeineCacheMetrics#monitor} for us when {@code recordStats()} is enabled
 * on the Caffeine spec. Our explicit {@link MeterBinder} bean additionally validates
 * registration and provides a hook for future custom tags.
 *
 * <p>The {@link MeterBinder} uses the same tags as Spring Boot ({@code cache},
 * {@code cache.manager}, {@code name}) to avoid duplicate metric registration warnings
 * in the Prometheus meter registry, which enforces consistent tag sets per metric name.
 *
 * <p>All caches use the same Caffeine configuration:
 * <ul>
 *   <li>Maximum size: 10,000 entries</li>
 *   <li>Expire after write: 5 minutes</li>
 *   <li>Stats recording: enabled (required for CaffeineCacheMetrics)</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableCaching
@RequiredArgsConstructor
public class CacheConfiguration {

    private static final List<String> CACHE_NAMES = List.of("credentials", "acl_rules", "sessions");
    private static final String CACHE_MANAGER_NAME = "cacheManager";

    private final MeterRegistry meterRegistry;

    @Bean
    public CaffeineCacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats());
        manager.setCacheNames(CACHE_NAMES);
        log.info("Caffeine cache manager initialized with caches: {}", CACHE_NAMES);
        return manager;
    }

    /**
     * Explicitly registers CaffeineCacheMetrics for each cache (per D-16).
     *
     * <p>Uses the same tag keys as Spring Boot's {@code CaffeineCacheMeterBinderProvider}
     * ({@code cache}, {@code cache.manager}, {@code name}) to avoid metric name conflicts
     * in the Prometheus registry, which requires all meters with the same name to share
     * the same tag key set.
     */
    @Bean
    public MeterBinder caffeineCacheMetrics(CaffeineCacheManager cacheManager) {
        return registry -> {
            for (String cacheName : CACHE_NAMES) {
                Cache cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    Object nativeCache = cache.getNativeCache();
                    if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
                        // Use the same tags as Spring Boot's CaffeineCacheMeterBinderProvider
                        // to prevent metric tag collision in Prometheus registry
                        List<Tag> tags = List.of(
                                Tag.of("cache", cacheName),
                                Tag.of("cache.manager", CACHE_MANAGER_NAME),
                                Tag.of("name", cacheName)
                        );
                        CaffeineCacheMetrics.monitor(registry, caffeineCache, cacheName, tags);
                    }
                }
            }
            log.info("CaffeineCacheMetrics registered for caches: {}", CACHE_NAMES);
        };
    }

}
