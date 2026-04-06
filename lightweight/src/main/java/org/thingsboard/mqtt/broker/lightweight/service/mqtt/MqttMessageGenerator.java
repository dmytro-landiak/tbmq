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

import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttMessage;

/**
 * Factory for MQTT response messages sent by the broker to clients.
 *
 * <p>Encapsulates Netty's {@code MqttMessageBuilders} to keep the actor layer
 * decoupled from Netty MQTT codec internals.
 *
 * <p>Methods will be extended in subsequent plans (Plan 04: SUBACK, UNSUBACK,
 * PUBLISH, PUBACK, PUBREC, PUBREL, PUBCOMP).
 */
public interface MqttMessageGenerator {

    /**
     * Creates a CONNACK response.
     *
     * @param returnCode     the connection return code
     * @param sessionPresent whether a prior session exists for this clientId
     * @return a {@link MqttConnAckMessage} ready to write to the channel
     */
    MqttConnAckMessage createConnAck(MqttConnectReturnCode returnCode, boolean sessionPresent);

    /**
     * Creates a PINGRESP response.
     *
     * @return a {@link MqttMessage} with {@code PINGRESP} fixed header
     */
    MqttMessage createPingResp();

}
