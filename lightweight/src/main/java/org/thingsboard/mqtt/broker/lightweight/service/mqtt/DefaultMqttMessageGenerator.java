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

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttPublishVariableHeader;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttUnsubAckMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default implementation of {@link MqttMessageGenerator} using Netty's
 * {@link MqttMessageBuilders} to construct MQTT response packets.
 */
@Slf4j
@Service
public class DefaultMqttMessageGenerator implements MqttMessageGenerator {

    @Override
    public MqttConnAckMessage createConnAck(MqttConnectReturnCode returnCode, boolean sessionPresent) {
        return MqttMessageBuilders.connAck()
                .returnCode(returnCode)
                .sessionPresent(sessionPresent)
                .build();
    }

    @Override
    public MqttMessage createPingResp() {
        return new MqttMessage(new MqttFixedHeader(MqttMessageType.PINGRESP, false, MqttQoS.AT_MOST_ONCE, false, 0));
    }

    @Override
    public MqttSubAckMessage createSubAck(int packetId, List<Integer> grantedQosList) {
        MqttMessageBuilders.SubAckBuilder builder = MqttMessageBuilders.subAck().packetId(packetId);
        for (int qos : grantedQosList) {
            builder.addGrantedQos(MqttQoS.valueOf(qos));
        }
        return builder.build();
    }

    @Override
    public MqttUnsubAckMessage createUnsubAck(int packetId) {
        return MqttMessageBuilders.unsubAck().packetId(packetId).build();
    }

    @Override
    public MqttPublishMessage createPublish(String topic, int qos, byte[] payload, boolean retain, boolean dup, int packetId) {
        MqttFixedHeader fixedHeader = new MqttFixedHeader(
                MqttMessageType.PUBLISH, dup, MqttQoS.valueOf(qos), retain, 0);
        MqttPublishVariableHeader variableHeader = new MqttPublishVariableHeader(topic, packetId);
        return new MqttPublishMessage(fixedHeader, variableHeader, Unpooled.wrappedBuffer(payload));
    }

    @Override
    public MqttMessage createPubAck(int packetId) {
        MqttFixedHeader fixedHeader = new MqttFixedHeader(
                MqttMessageType.PUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0);
        return new MqttMessage(fixedHeader, MqttMessageIdVariableHeader.from(packetId));
    }

    @Override
    public MqttMessage createPubRec(int packetId) {
        MqttFixedHeader fixedHeader = new MqttFixedHeader(
                MqttMessageType.PUBREC, false, MqttQoS.AT_MOST_ONCE, false, 0);
        return new MqttMessage(fixedHeader, MqttMessageIdVariableHeader.from(packetId));
    }

    @Override
    public MqttMessage createPubRel(int packetId) {
        // Per MQTT spec section 3.6.1: PUBREL fixed header has QoS = 1
        MqttFixedHeader fixedHeader = new MqttFixedHeader(
                MqttMessageType.PUBREL, false, MqttQoS.AT_LEAST_ONCE, false, 0);
        return new MqttMessage(fixedHeader, MqttMessageIdVariableHeader.from(packetId));
    }

    @Override
    public MqttMessage createPubComp(int packetId) {
        MqttFixedHeader fixedHeader = new MqttFixedHeader(
                MqttMessageType.PUBCOMP, false, MqttQoS.AT_MOST_ONCE, false, 0);
        return new MqttMessage(fixedHeader, MqttMessageIdVariableHeader.from(packetId));
    }

}
