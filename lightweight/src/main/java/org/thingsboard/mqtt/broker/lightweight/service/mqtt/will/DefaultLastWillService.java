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
package org.thingsboard.mqtt.broker.lightweight.service.mqtt.will;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link LastWillService} backed by a {@link ConcurrentHashMap}.
 *
 * <p>Keyed by session UUID rather than clientId, which ensures correct behavior
 * during client takeover: the old session's will is suppressed independently
 * from the new session (they have different UUIDs).
 *
 * <p>Thread safety: ConcurrentHashMap provides thread-safe reads and writes without locking.
 */
@Service
@Slf4j
public class DefaultLastWillService implements LastWillService {

    private final ConcurrentHashMap<UUID, WillMessage> willMessages = new ConcurrentHashMap<>();

    @Override
    public void storeWill(UUID sessionId, WillMessage willMessage) {
        willMessages.put(sessionId, willMessage);
        log.debug("Stored LWT for session {} on topic '{}'", sessionId, willMessage.getTopicName());
    }

    @Override
    public Optional<WillMessage> removeWill(UUID sessionId) {
        WillMessage will = willMessages.remove(sessionId);
        if (will != null) {
            log.debug("Retrieved LWT for session {} (topic: '{}')", sessionId, will.getTopicName());
        }
        return Optional.ofNullable(will);
    }

    @Override
    public void removeWillWithoutDelivery(UUID sessionId) {
        WillMessage removed = willMessages.remove(sessionId);
        if (removed != null) {
            log.debug("Removed LWT for session {} without delivery (topic: '{}')", sessionId, removed.getTopicName());
        }
    }

    @PreDestroy
    public void destroy() {
        int remaining = willMessages.size();
        willMessages.clear();
        if (remaining > 0) {
            log.info("Cleared {} pending LWT entries on shutdown", remaining);
        }
    }

}
