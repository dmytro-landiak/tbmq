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
package org.thingsboard.mqtt.broker.lightweight.service.mqtt;

import lombok.Builder;
import lombok.Data;

/**
 * Immutable representation of an MQTT PUBLISH message.
 *
 * <p>Used throughout the broker for in-memory message routing.
 * Built from incoming Netty {@code MqttPublishMessage} objects and passed
 * through the actor system for delivery to subscribers.
 */
@Data
@Builder
public class PublishMsg {

    /** MQTT topic name (UTF-8, no wildcards for publish). */
    private final String topicName;

    /** Quality of Service level: 0, 1, or 2. */
    private final int qos;

    /** Message payload bytes (may be empty but never null). */
    private final byte[] payload;

    /** Whether this is a retained message. */
    private final boolean retain;

    /** DUP flag — set on retransmissions for QoS 1/2. */
    private final boolean dup;

    /** Packet identifier (only meaningful for QoS 1 and 2; 0 for QoS 0). */
    private final int packetId;

}
