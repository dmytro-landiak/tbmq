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

import io.netty.handler.codec.mqtt.MqttProperties;
import lombok.Builder;
import lombok.Data;

/**
 * Immutable representation of a retained MQTT message.
 *
 * <p>Per MQTT 3.1.1 spec section 3.3.1.3: a broker MUST store retained messages and their QoS.
 * In R1, retained messages are in-memory only and do not survive broker restarts.
 */
@Data
@Builder
public class RetainedMsg {

    /** The topic name this message is retained on. */
    private final String topicName;

    /** QoS level of the retained message (0, 1, or 2). */
    private final int qos;

    /** Message payload bytes (non-empty; empty payload clears the retained message). */
    private final byte[] payload;

    /** Timestamp when this retained message was stored. Used for message expiry calculation. */
    @Builder.Default
    private final long createdTime = System.currentTimeMillis();

    /** MQTT 5.0 properties (user properties, payload format, content type, expiry interval). Never null. */
    @Builder.Default
    private final MqttProperties properties = MqttProperties.NO_PROPERTIES;

}
