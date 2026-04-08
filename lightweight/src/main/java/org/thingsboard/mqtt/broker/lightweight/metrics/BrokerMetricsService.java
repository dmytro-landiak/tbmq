package org.thingsboard.mqtt.broker.lightweight.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Central service for custom broker metric registrations.
 *
 * <p>Per D-15: Spring Boot Actuator + Micrometer with Prometheus registry.
 * This service is the designated place for future Phase 2+ metric registrations:
 * message rates, subscription counts, QoS 1/2 re-delivery rates, etc.
 *
 * <p>Phase 1 metrics already registered by their respective components:
 * <ul>
 *   <li>{@code mqtt.connections.active} — registered in {@code ConnectionCountHandler}</li>
 *   <li>{@code rocksdb.write.latency} — registered in {@code DefaultRocksDbStorage}</li>
 *   <li>{@code rocksdb.read.latency} — registered in {@code DefaultRocksDbStorage}</li>
 *   <li>{@code cache.*} — registered in {@code CacheConfiguration}</li>
 * </ul>
 *
 * <p>This service registers a placeholder counter for MQTT messages that will be
 * incremented in Phase 2 when the MQTT protocol handler is implemented.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerMetricsService {

    private final MeterRegistry meterRegistry;

    @PostConstruct
    public void init() {
        // Register total messages counter — will be incremented in Phase 2
        // when MQTT PUBLISH handler is implemented
        Counter.builder("mqtt.messages.received.total")
                .description("Total MQTT messages received")
                .register(meterRegistry);

        // Register dropped messages counter — Micrometer deduplicates by name, so this ensures
        // the counter is visible at startup even before the dispatch service starts
        Counter.builder("mqtt.dispatch.dropped.total")
                .description("Messages dropped due to full dispatch queue")
                .register(meterRegistry);

        log.info("Broker metrics service initialized");
    }

}
