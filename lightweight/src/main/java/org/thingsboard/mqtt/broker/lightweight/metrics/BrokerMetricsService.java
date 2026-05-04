/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
        // Register total messages received counter — incremented in ClientActor.processPublish() and processPubRel()
        Counter.builder("mqtt.messages.received.total")
                .description("Total MQTT messages received")
                .register(meterRegistry);

        // Register total messages delivered counter — incremented in ClientActor.processDeliver()
        Counter.builder("mqtt.messages.delivered.total")
                .description("Total MQTT messages delivered to subscribers")
                .register(meterRegistry);

        // Register dropped messages counter — Micrometer deduplicates by name, so this ensures
        // the counter is visible at startup even before the dispatch service starts
        Counter.builder("mqtt.dispatch.dropped.total")
                .description("Messages dropped due to full dispatch queue")
                .register(meterRegistry);

        // Register LWT-fired counter — incremented in ClientActor when a Last Will is delivered
        Counter.builder("mqtt.lwt.fired.total")
                .description("Total Last Will Testament messages fired (delivered to subscribers)")
                .register(meterRegistry);

        log.info("Broker metrics service initialized");
    }

}
