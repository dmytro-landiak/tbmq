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
package org.thingsboard.mqtt.broker.lightweight.session;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-session bidirectional topic alias context for MQTT 5.0.
 *
 * <p>Manages two alias mappings:
 * <ul>
 *   <li><b>clientMappings</b> (inbound) — client-to-broker direction: alias number → topic name.
 *       Populated when the client sends PUBLISH with a non-empty topic name and a topic alias.
 *       Used to resolve subsequent PUBLISH packets where the topic name is empty.</li>
 *   <li><b>serverMappings</b> (outbound) — broker-to-client direction: topic name → alias number.
 *       Populated lazily as the broker assigns aliases for outbound PUBLISH to this client.</li>
 * </ul>
 *
 * <p>Per [MQTT-3.3.2-7]: Topic Alias 0 is a protocol error. Per [MQTT-3.3.2-8]: a Topic Alias
 * greater than Topic Alias Maximum (from CONNECT) is a protocol error. Both cases trigger
 * {@link #validateTopicAlias(int)}, which throws a {@link RuntimeException} that the caller
 * catches to disconnect the client with reason code {@code TOPIC_ALIAS_INVALID}.
 *
 * <p>The static {@link #DISABLED_TOPIC_ALIASES} singleton represents a disabled context
 * (MQTT 3.1.1 clients, or when the client advertises Topic Alias Maximum = 0).
 */
@Slf4j
@Getter
public class TopicAliasCtx {

    /**
     * Singleton representing disabled topic alias support.
     * Used for MQTT 3.1.1 clients and MQTT 5.0 clients with topicAliasMax = 0.
     */
    public static final TopicAliasCtx DISABLED_TOPIC_ALIASES = new TopicAliasCtx(false, 0);

    /** Whether topic alias support is enabled for this session. */
    private final boolean enabled;

    /** Maximum alias number this session can use (from client's CONNECT Topic Alias Maximum). */
    private final int maxTopicAlias;

    /** Inbound alias map: alias number → topic name (client sends aliases to broker). */
    private final ConcurrentHashMap<Integer, String> clientMappings;

    /** Outbound alias map: topic name → alias number (broker sends aliases to client). */
    private final ConcurrentHashMap<String, Integer> serverMappings;

    /** Next alias value to allocate for outbound delivery. Starts at 1. */
    private final AtomicInteger nextServerAlias;

    /**
     * Creates a new topic alias context.
     *
     * @param enabled       whether topic alias support is active
     * @param maxTopicAlias the maximum alias number (0 means disabled)
     */
    public TopicAliasCtx(boolean enabled, int maxTopicAlias) {
        this.enabled = enabled;
        this.maxTopicAlias = maxTopicAlias;
        this.clientMappings = new ConcurrentHashMap<>();
        this.serverMappings = new ConcurrentHashMap<>();
        this.nextServerAlias = new AtomicInteger(1);
    }

    /**
     * Resolves the topic name from an inbound PUBLISH using the alias context.
     *
     * <p>Logic per MQTT 5.0 spec [MQTT-3.3.2-7, 3.3.2-8, 3.3.2-9, 3.3.2-10]:
     * <ol>
     *   <li>If alias is provided and non-empty topic name: store the mapping, return topic name.</li>
     *   <li>If alias is provided and empty topic name: look up existing mapping, return it.</li>
     *   <li>If no alias present: return {@code null} (caller uses the topic name directly).</li>
     * </ol>
     *
     * @param topicName  the topic name from the PUBLISH (may be empty if alias is used)
     * @param topicAlias the topic alias from PUBLISH properties (0 = no alias)
     * @return resolved topic name, or {@code null} if no alias was present in the packet
     * @throws RuntimeException if the alias is invalid (0 or exceeds maximum)
     */
    public String getTopicNameByAlias(String topicName, int topicAlias) {
        if (!enabled || topicAlias == 0) {
            return null;
        }

        validateTopicAlias(topicAlias);

        if (!topicName.isEmpty()) {
            // Client is establishing or updating the alias mapping
            clientMappings.put(topicAlias, topicName);
            return topicName;
        } else {
            // Client is using an existing alias — look up stored topic name
            String resolved = clientMappings.get(topicAlias);
            if (resolved == null) {
                throw new RuntimeException("Unknown Topic Alias: " + topicAlias);
            }
            return resolved;
        }
    }

    /**
     * Returns the topic alias to use for an outbound PUBLISH to this client,
     * or {@code 0} if no alias should be used.
     *
     * <p>Assigns new aliases lazily up to {@link #maxTopicAlias}. Once all slots
     * are used, existing aliases are reused but no new ones are allocated.
     *
     * @param topicName           the outbound topic name
     * @param minTopicNameLength  minimum topic name length to qualify for aliasing
     * @return assigned alias (existing or new), or {@code 0} if no alias applies
     */
    public int getTopicAliasForPublish(String topicName, int minTopicNameLength) {
        if (!enabled || topicName.length() < minTopicNameLength) {
            return 0;
        }

        Integer existing = serverMappings.get(topicName);
        if (existing != null) {
            return existing;
        }

        // Allocate a new alias slot if capacity remains
        if (serverMappings.size() < maxTopicAlias) {
            int alias = nextServerAlias.getAndIncrement();
            if (alias <= maxTopicAlias) {
                serverMappings.put(topicName, alias);
                return alias;
            }
            // Race: someone incremented past max — do not assign
        }

        return 0;
    }

    /**
     * Validates that a topic alias from the client is within the allowed range.
     *
     * <p>Per [MQTT-3.3.2-7]: Topic Alias 0 is a protocol error.
     * Per [MQTT-3.3.2-8]: Topic Alias > Topic Alias Maximum is a protocol error.
     *
     * @param topicAlias the alias value to validate
     * @throws RuntimeException if the alias is invalid
     */
    public void validateTopicAlias(int topicAlias) {
        if (topicAlias == 0) {
            throw new RuntimeException("Topic Alias is zero — protocol error");
        }
        if (topicAlias > maxTopicAlias) {
            throw new RuntimeException("Topic Alias " + topicAlias + " exceeds maximum " + maxTopicAlias);
        }
    }

}
