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

import lombok.Builder;
import lombok.Data;

/**
 * Immutable representation of an MQTT Last Will and Testament (LWT) message.
 *
 * <p>Stored at CONNECT time when the will flag is set. Published to subscribers
 * when the client disconnects ungracefully (keep-alive expiry, channel close, error).
 * Suppressed on clean DISCONNECT or client takeover.
 *
 * <p>Per MQTT 3.1.1 spec section 3.1.2.5.
 */
@Data
@Builder
public class WillMessage {

    /** The topic name for the will message. */
    private final String topicName;

    /** The will message payload bytes. */
    private final byte[] payload;

    /** QoS level for will message delivery (0, 1, or 2). */
    private final int qos;

    /** Whether to retain the will message. */
    private final boolean retain;

}
