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
package org.thingsboard.mqtt.broker.lightweight.server;

import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttUnsubscribeMessage;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorSystem;
import org.thingsboard.mqtt.broker.lightweight.actors.TbTypeActorId;
import org.thingsboard.mqtt.broker.lightweight.actors.client.ClientActorCreator;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.MqttConnectMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.MqttDisconnectMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.MqttPubAckMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.MqttPubCompMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.MqttPubRecMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.MqttPubRelMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.MqttPublishMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.MqttSubscribeMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.MqttUnsubscribeMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.PingMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.SessionCloseMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.SessionInitMsg;
import org.thingsboard.mqtt.broker.lightweight.config.MqttConfiguration;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.MqttMessageGenerator;
import org.thingsboard.mqtt.broker.lightweight.service.subscription.SubscriptionRegistry;
import org.thingsboard.mqtt.broker.lightweight.session.ClientSessionCtx;
import org.thingsboard.mqtt.broker.lightweight.session.ClientSessionRegistry;
import org.thingsboard.mqtt.broker.lightweight.session.DisconnectReasonType;
import org.thingsboard.mqtt.broker.lightweight.session.SessionState;

import java.util.UUID;

/**
 * Netty channel handler that routes inbound MQTT messages to the actor system.
 *
 * <p>NOT @Sharable — a new instance must be created per channel connection.
 *
 * <p>Handles:
 * <ul>
 *   <li>CONNECT — creates actor, sends SessionInitMsg + MqttConnectMsg</li>
 *   <li>DISCONNECT — sends MqttDisconnectMsg to actor</li>
 *   <li>PINGREQ — sends PingMsg to actor</li>
 *   <li>PUBLISH/SUBSCRIBE/UNSUBSCRIBE/PUBACK/PUBREC/PUBREL/PUBCOMP — routed to actor</li>
 *   <li>Keep-alive timeout via IdleStateEvent</li>
 *   <li>Channel close via channelInactive</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class MqttSessionHandler extends ChannelInboundHandlerAdapter {

    private static final String CLIENT_DISPATCHER = "client-dispatcher";

    private final TbActorSystem actorSystem;
    private final ClientSessionRegistry sessionRegistry;
    private final MqttMessageGenerator messageGenerator;
    private final MqttConfiguration mqttConfig;
    private final SubscriptionRegistry subscriptionRegistry;

    /** Session context — null until CONNECT is processed. */
    private ClientSessionCtx sessionCtx;

    /** True once a CONNECT packet has been successfully processed. */
    private boolean connected;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (!(msg instanceof MqttMessage mqttMessage)) {
                log.warn("Received non-MQTT message, disconnecting");
                disconnect(ctx, DisconnectReasonType.ON_PROTOCOL_ERROR, "Unknown message type");
                return;
            }
            DecoderResult result = mqttMessage.decoderResult();
            if (!result.isSuccess()) {
                if (result.cause() instanceof TooLongFrameException) {
                    log.warn("MQTT packet too large, disconnecting");
                    disconnect(ctx, DisconnectReasonType.ON_PACKET_TOO_LARGE, "Packet too large");
                } else {
                    log.warn("Malformed MQTT packet: {}", result.cause() != null ? result.cause().getMessage() : "unknown");
                    disconnect(ctx, DisconnectReasonType.ON_MALFORMED_PACKET,
                            result.cause() != null ? result.cause().getMessage() : "Malformed packet");
                }
                return;
            }
            processMqttMsg(ctx, mqttMessage);
        } finally {
            ReferenceCountUtil.safeRelease(msg);
        }
    }

    private void processMqttMsg(ChannelHandlerContext ctx, MqttMessage msg) {
        MqttMessageType msgType = msg.fixedHeader().messageType();

        if (!connected && msgType != MqttMessageType.CONNECT) {
            log.warn("First packet must be CONNECT, received {}, disconnecting", msgType);
            disconnect(ctx, DisconnectReasonType.ON_PROTOCOL_ERROR,
                    "First packet must be CONNECT, got: " + msgType);
            return;
        }

        switch (msgType) {
            case CONNECT -> processConnect(ctx, (MqttConnectMessage) msg);
            case DISCONNECT -> processDisconnect(ctx);
            case PINGREQ -> processPing();
            case PUBLISH -> processPublish((MqttPublishMessage) msg);
            case SUBSCRIBE -> processSubscribe((MqttSubscribeMessage) msg);
            case UNSUBSCRIBE -> processUnsubscribe((MqttUnsubscribeMessage) msg);
            case PUBACK -> processPubAck(msg);
            case PUBREC -> processPubRec(msg);
            case PUBREL -> processPubRel(msg);
            case PUBCOMP -> processPubComp(msg);
            default -> {
                log.warn("Unhandled MQTT message type: {}", msgType);
                disconnect(ctx, DisconnectReasonType.ON_PROTOCOL_ERROR, "Unhandled message type: " + msgType);
            }
        }
    }

    private void processPublish(MqttPublishMessage mqttPublishMessage) {
        if (sessionCtx == null) {
            return;
        }
        // CRITICAL: Copy payload bytes BEFORE the finally block releases the ByteBuf
        byte[] payloadBytes = ByteBufUtil.getBytes(mqttPublishMessage.payload());
        String topicName = mqttPublishMessage.variableHeader().topicName();
        int qos = mqttPublishMessage.fixedHeader().qosLevel().value();
        boolean retain = mqttPublishMessage.fixedHeader().isRetain();
        boolean dup = mqttPublishMessage.fixedHeader().isDup();
        int packetId = mqttPublishMessage.variableHeader().packetId();

        TbTypeActorId actorId = new TbTypeActorId("client", sessionCtx.getClientId());
        actorSystem.tell(actorId, new MqttPublishMsg(topicName, qos, payloadBytes, retain, dup, packetId));
    }

    private void processSubscribe(MqttSubscribeMessage mqttSubscribeMessage) {
        if (sessionCtx == null) {
            return;
        }
        TbTypeActorId actorId = new TbTypeActorId("client", sessionCtx.getClientId());
        actorSystem.tell(actorId, new MqttSubscribeMsg(mqttSubscribeMessage));
    }

    private void processUnsubscribe(MqttUnsubscribeMessage mqttUnsubscribeMessage) {
        if (sessionCtx == null) {
            return;
        }
        TbTypeActorId actorId = new TbTypeActorId("client", sessionCtx.getClientId());
        actorSystem.tell(actorId, new MqttUnsubscribeMsg(mqttUnsubscribeMessage));
    }

    private void processPubAck(MqttMessage msg) {
        if (sessionCtx == null) {
            return;
        }
        int packetId = ((MqttMessageIdVariableHeader) msg.variableHeader()).messageId();
        TbTypeActorId actorId = new TbTypeActorId("client", sessionCtx.getClientId());
        actorSystem.tell(actorId, new MqttPubAckMsg(packetId));
    }

    private void processPubRec(MqttMessage msg) {
        if (sessionCtx == null) {
            return;
        }
        int packetId = ((MqttMessageIdVariableHeader) msg.variableHeader()).messageId();
        TbTypeActorId actorId = new TbTypeActorId("client", sessionCtx.getClientId());
        actorSystem.tell(actorId, new MqttPubRecMsg(packetId));
    }

    private void processPubRel(MqttMessage msg) {
        if (sessionCtx == null) {
            return;
        }
        int packetId = ((MqttMessageIdVariableHeader) msg.variableHeader()).messageId();
        TbTypeActorId actorId = new TbTypeActorId("client", sessionCtx.getClientId());
        actorSystem.tell(actorId, new MqttPubRelMsg(packetId));
    }

    private void processPubComp(MqttMessage msg) {
        if (sessionCtx == null) {
            return;
        }
        int packetId = ((MqttMessageIdVariableHeader) msg.variableHeader()).messageId();
        TbTypeActorId actorId = new TbTypeActorId("client", sessionCtx.getClientId());
        actorSystem.tell(actorId, new MqttPubCompMsg(packetId));
    }

    private void processConnect(ChannelHandlerContext ctx, MqttConnectMessage connectMsg) {
        if (connected) {
            log.warn("Received second CONNECT on already-connected channel, disconnecting");
            disconnect(ctx, DisconnectReasonType.ON_PROTOCOL_ERROR, "Second CONNECT on same channel");
            return;
        }

        String clientId = connectMsg.payload().clientIdentifier();
        if (clientId == null || clientId.isEmpty()) {
            // Per MQTT 3.1.1 spec 3.1.3.1: assign broker-generated ID if cleanSession=true
            clientId = UUID.randomUUID().toString();
            log.debug("Empty clientId received, assigned broker-generated ID: {}", clientId);
        }

        sessionCtx = new ClientSessionCtx(UUID.randomUUID(), ctx);
        sessionCtx.setClientId(clientId);

        TbTypeActorId actorId = new TbTypeActorId("client", clientId);
        actorSystem.createRootActor(CLIENT_DISPATCHER, new ClientActorCreator(
                clientId, sessionRegistry, messageGenerator, mqttConfig, subscriptionRegistry));

        actorSystem.tell(actorId, new SessionInitMsg(sessionCtx));
        actorSystem.tell(actorId, new MqttConnectMsg(connectMsg, sessionCtx));

        connected = true;
    }

    private void processDisconnect(ChannelHandlerContext ctx) {
        if (sessionCtx != null) {
            TbTypeActorId actorId = new TbTypeActorId("client", sessionCtx.getClientId());
            actorSystem.tell(actorId, new MqttDisconnectMsg(DisconnectReasonType.ON_DISCONNECT_MSG, "Client disconnected"));
        } else {
            ctx.close();
        }
    }

    private void processPing() {
        if (sessionCtx != null) {
            TbTypeActorId actorId = new TbTypeActorId("client", sessionCtx.getClientId());
            actorSystem.tell(actorId, PingMsg.INSTANCE);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idleEvt && idleEvt.state() == IdleState.READER_IDLE) {
            log.info("[{}] Keep-alive timeout, disconnecting",
                    sessionCtx != null ? sessionCtx.getClientId() : "unknown");
            disconnect(ctx, DisconnectReasonType.ON_KEEP_ALIVE, "Keep-alive timeout");
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (sessionCtx != null && sessionCtx.getState() != SessionState.DISCONNECTED) {
            TbTypeActorId actorId = new TbTypeActorId("client", sessionCtx.getClientId());
            actorSystem.tell(actorId, new SessionCloseMsg(DisconnectReasonType.ON_CHANNEL_CLOSED));
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[{}] Exception in MQTT session handler: {}",
                sessionCtx != null ? sessionCtx.getClientId() : "unknown",
                cause.getMessage(), cause);
        disconnect(ctx, DisconnectReasonType.ON_ERROR, cause.getMessage());
    }

    /**
     * Closes the channel and marks the session as disconnecting.
     */
    private void disconnect(ChannelHandlerContext ctx, DisconnectReasonType reasonType, String reason) {
        log.debug("[{}] Disconnecting: {} — {}",
                sessionCtx != null ? sessionCtx.getClientId() : "unknown", reasonType, reason);
        if (sessionCtx != null) {
            sessionCtx.setState(SessionState.DISCONNECTING);
        }
        ctx.close();
    }

}
