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

import java.util.List;
import java.util.Optional;

/**
 * Manages in-memory retained messages per MQTT 3.1.1 spec section 3.3.1.3.
 *
 * <p>Operations:
 * <ul>
 *   <li>Set: store a new retained message for a topic, replacing any previous one</li>
 *   <li>Clear: delete the retained message for a topic (triggered by empty payload publish)</li>
 *   <li>Get (exact): retrieve the retained message for an exact topic match</li>
 *   <li>Get (wildcard): retrieve all retained messages matching a topic filter (Phase 3)</li>
 * </ul>
 *
 * <p>In R1, all retained messages are in-memory only (D-09: no persistence across restarts).
 */
public interface RetainedMsgService {

    /**
     * Stores or replaces the retained message for the given topic.
     *
     * @param topic the exact topic name
     * @param msg   the retained message to store
     */
    void setRetainedMessage(String topic, RetainedMsg msg);

    /**
     * Removes the retained message for the given topic.
     * Called when a PUBLISH with retain=1 and empty payload is received.
     *
     * @param topic the exact topic name
     */
    void clearRetainedMessage(String topic);

    /**
     * Returns the retained message for the given topic, if any.
     *
     * @param topic the exact topic name (no wildcards)
     * @return an Optional containing the retained message, or empty if none exists
     */
    Optional<RetainedMsg> getRetainedMessage(String topic);

    /**
     * Returns all retained messages whose topic matches the given topic filter.
     * Supports wildcard filters (+ and #) via the retained message trie.
     *
     * <p>Used when delivering retained messages on subscribe — per D-08, wildcard filters
     * must match all retained messages stored under matching topic hierarchies.
     *
     * @param topicFilter the MQTT topic filter (may contain wildcards)
     * @return list of matching retained messages (never null, may be empty)
     */
    List<RetainedMsg> getRetainedMessages(String topicFilter);

}
