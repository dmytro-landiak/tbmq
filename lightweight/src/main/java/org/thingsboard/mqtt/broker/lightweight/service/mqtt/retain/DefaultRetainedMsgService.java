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
package org.thingsboard.mqtt.broker.lightweight.service.mqtt.retain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link RetainedMsgService} backed by a {@link ConcurrentHashMap}.
 *
 * <p>Per D-09: retained messages are stored in memory for the broker lifetime only.
 * No persistence across restarts in R1 — this is intentional for the lightweight variant.
 *
 * <p>Thread safety: ConcurrentHashMap provides thread-safe reads and writes without locking.
 */
@Service
@Slf4j
public class DefaultRetainedMsgService implements RetainedMsgService {

    private final ConcurrentHashMap<String, RetainedMsg> retainedMessages = new ConcurrentHashMap<>();

    @Override
    public void setRetainedMessage(String topic, RetainedMsg msg) {
        retainedMessages.put(topic, msg);
        log.debug("Stored retained message for topic '{}' (qos={}, {} bytes)", topic, msg.getQos(), msg.getPayload().length);
    }

    @Override
    public void clearRetainedMessage(String topic) {
        retainedMessages.remove(topic);
        log.debug("Cleared retained message for topic '{}'", topic);
    }

    @Override
    public Optional<RetainedMsg> getRetainedMessage(String topic) {
        return Optional.ofNullable(retainedMessages.get(topic));
    }

}
