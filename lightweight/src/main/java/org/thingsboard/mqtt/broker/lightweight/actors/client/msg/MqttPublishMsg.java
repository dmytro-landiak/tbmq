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
package org.thingsboard.mqtt.broker.lightweight.actors.client.msg;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.thingsboard.mqtt.broker.lightweight.actors.MsgType;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorMsg;

/**
 * Carries an inbound MQTT PUBLISH message to the {@link org.thingsboard.mqtt.broker.lightweight.actors.client.ClientActor}.
 *
 * <p>IMPORTANT: The payload bytes are pre-copied from the Netty ByteBuf in the session handler
 * BEFORE the ByteBuf is released. Never pass the raw ByteBuf reference through the actor system.
 */
@Getter
@RequiredArgsConstructor
public class MqttPublishMsg implements TbActorMsg {

    /** MQTT topic name from the PUBLISH packet. */
    private final String topicName;

    /** Quality of Service level: 0, 1, or 2. */
    private final int qos;

    /** Message payload bytes — pre-copied from Netty ByteBuf before release. */
    private final byte[] payload;

    /** Whether this message is a retained message. */
    private final boolean retain;

    /** DUP flag — set on retransmissions for QoS 1/2. */
    private final boolean dup;

    /** Packet identifier (only meaningful for QoS 1 and 2; 0 for QoS 0). */
    private final int packetId;

    @Override
    public MsgType getMsgType() {
        return MsgType.PUBLISH_MSG;
    }

}
