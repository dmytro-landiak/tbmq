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
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttReasonCodes;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttUnsubAckMessage;

import java.util.List;

/**
 * Factory for MQTT response messages sent by the broker to clients.
 *
 * <p>Encapsulates Netty's {@code MqttMessageBuilders} to keep the actor layer
 * decoupled from Netty MQTT codec internals.
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

    /**
     * Creates a SUBACK response.
     *
     * @param packetId       the packet identifier from the SUBSCRIBE packet
     * @param grantedQosList list of granted QoS values (one per requested subscription)
     * @return a {@link MqttSubAckMessage} ready to write to the channel
     */
    MqttSubAckMessage createSubAck(int packetId, List<Integer> grantedQosList);

    /**
     * Creates an UNSUBACK response.
     *
     * @param packetId the packet identifier from the UNSUBSCRIBE packet
     * @return a {@link MqttUnsubAckMessage} ready to write to the channel
     */
    MqttUnsubAckMessage createUnsubAck(int packetId);

    /**
     * Creates a PUBLISH message for delivery to a subscriber.
     *
     * @param topic    the topic name
     * @param qos      the QoS level for delivery (0, 1, or 2)
     * @param payload  the message payload bytes
     * @param retain   whether the retain flag should be set
     * @param dup      whether the DUP flag should be set (retransmission)
     * @param packetId the packet identifier (0 for QoS 0)
     * @return a {@link MqttPublishMessage} ready to write to the channel
     */
    MqttPublishMessage createPublish(String topic, int qos, byte[] payload, boolean retain, boolean dup, int packetId);

    /**
     * Creates a PUBACK response for a QoS 1 PUBLISH.
     *
     * @param packetId the packet identifier from the PUBLISH
     * @return a {@link MqttMessage} with {@code PUBACK} fixed header
     */
    MqttMessage createPubAck(int packetId);

    /**
     * Creates a PUBREC response for a QoS 2 PUBLISH (step 1 of 2 in QoS 2 handshake).
     *
     * @param packetId the packet identifier from the PUBLISH
     * @return a {@link MqttMessage} with {@code PUBREC} fixed header
     */
    MqttMessage createPubRec(int packetId);

    /**
     * Creates a PUBREL message for the QoS 2 outbound handshake (step 2 of 3).
     *
     * <p>Per MQTT spec, PUBREL uses QoS 1 in its fixed header.
     *
     * @param packetId the packet identifier
     * @return a {@link MqttMessage} with {@code PUBREL} fixed header
     */
    MqttMessage createPubRel(int packetId);

    /**
     * Creates a PUBCOMP response completing the QoS 2 inbound handshake (step 4 of 4).
     *
     * @param packetId the packet identifier
     * @return a {@link MqttMessage} with {@code PUBCOMP} fixed header
     */
    MqttMessage createPubComp(int packetId);

    // -------------------------------------------------------------------------
    // MQTT 5.0 overloads — backward compatible, existing methods unchanged
    // -------------------------------------------------------------------------

    /**
     * Creates a CONNACK with MQTT 5.0 properties (topic alias max, receive max, session expiry override).
     *
     * @param returnCode     the connection return code
     * @param sessionPresent whether a prior session exists for this clientId
     * @param properties     MQTT 5.0 CONNACK properties
     * @return a {@link MqttConnAckMessage} ready to write to the channel
     */
    MqttConnAckMessage createConnAck(MqttConnectReturnCode returnCode, boolean sessionPresent, MqttProperties properties);

    /**
     * Creates a PUBLISH with MQTT 5.0 properties (user properties, expiry, payload format, etc.).
     *
     * @param topic      the topic name
     * @param qos        the QoS level for delivery (0, 1, or 2)
     * @param payload    the message payload bytes
     * @param retain     whether the retain flag should be set
     * @param dup        whether the DUP flag should be set (retransmission)
     * @param packetId   the packet identifier (0 for QoS 0)
     * @param properties MQTT 5.0 PUBLISH properties
     * @return a {@link MqttPublishMessage} ready to write to the channel
     */
    MqttPublishMessage createPublish(String topic, int qos, byte[] payload, boolean retain, boolean dup, int packetId, MqttProperties properties);

    /**
     * Creates a PUBACK with MQTT 5.0 reason code. Pass {@code null} reasonCode for MQTT 3.1.1.
     *
     * @param packetId   the packet identifier from the PUBLISH
     * @param reasonCode the PUBACK reason code (null for MQTT 3.1.1)
     * @return a {@link MqttMessage} with {@code PUBACK} fixed header
     */
    MqttMessage createPubAck(int packetId, MqttReasonCodes.PubAck reasonCode);

    /**
     * Creates a PUBREC with MQTT 5.0 reason code. Pass {@code null} reasonCode for MQTT 3.1.1.
     *
     * @param packetId   the packet identifier from the PUBLISH
     * @param reasonCode the PUBREC reason code (null for MQTT 3.1.1)
     * @return a {@link MqttMessage} with {@code PUBREC} fixed header
     */
    MqttMessage createPubRec(int packetId, MqttReasonCodes.PubRec reasonCode);

    /**
     * Creates a PUBREL with MQTT 5.0 reason code. Pass {@code null} reasonCode for MQTT 3.1.1.
     *
     * @param packetId   the packet identifier
     * @param reasonCode the PUBREL reason code (null for MQTT 3.1.1)
     * @return a {@link MqttMessage} with {@code PUBREL} fixed header
     */
    MqttMessage createPubRel(int packetId, MqttReasonCodes.PubRel reasonCode);

    /**
     * Creates a PUBCOMP with MQTT 5.0 reason code. Pass {@code null} reasonCode for MQTT 3.1.1.
     *
     * @param packetId   the packet identifier
     * @param reasonCode the PUBCOMP reason code (null for MQTT 3.1.1)
     * @return a {@link MqttMessage} with {@code PUBCOMP} fixed header
     */
    MqttMessage createPubComp(int packetId, MqttReasonCodes.PubComp reasonCode);

    /**
     * Creates a SUBACK with MQTT 5.0 properties.
     *
     * @param packetId       the packet identifier from the SUBSCRIBE packet
     * @param grantedQosList list of granted QoS values (one per requested subscription)
     * @param properties     MQTT 5.0 SUBACK properties
     * @return a {@link MqttSubAckMessage} ready to write to the channel
     */
    MqttSubAckMessage createSubAck(int packetId, List<Integer> grantedQosList, MqttProperties properties);

    /**
     * Creates an UNSUBACK with MQTT 5.0 reason codes and properties.
     *
     * @param packetId    the packet identifier from the UNSUBSCRIBE packet
     * @param reasonCodes per-topic-filter reason codes
     * @param properties  MQTT 5.0 UNSUBACK properties
     * @return a {@link MqttMessage} with {@code UNSUBACK} fixed header
     */
    MqttMessage createUnsubAck(int packetId, List<Short> reasonCodes, MqttProperties properties);

    /**
     * Creates a broker-initiated DISCONNECT with reason code (MQTT 5.0 only).
     *
     * @param reasonCode the disconnect reason code
     * @return a {@link MqttMessage} with {@code DISCONNECT} fixed header and reason code
     */
    MqttMessage createDisconnect(MqttReasonCodes.Disconnect reasonCode);

}
