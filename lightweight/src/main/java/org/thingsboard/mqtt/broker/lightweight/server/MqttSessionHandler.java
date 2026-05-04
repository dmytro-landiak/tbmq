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

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttReasonCodes;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttUnsubscribeMessage;
import io.netty.handler.codec.mqtt.MqttVersion;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorNotRegisteredException;
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
import org.thingsboard.mqtt.broker.lightweight.config.Mqtt5Configuration;
import org.thingsboard.mqtt.broker.lightweight.config.MqttConfiguration;
import org.thingsboard.mqtt.broker.lightweight.exception.ProtocolViolationException;
import org.thingsboard.mqtt.broker.lightweight.security.acl.AuthorizationRuleService;
import org.thingsboard.mqtt.broker.lightweight.security.auth.LightweightAuthService;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.MqttMessageGenerator;
import org.thingsboard.mqtt.broker.lightweight.service.dispatch.MsgDispatcherService;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.retain.RetainedMsgService;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.will.LastWillService;
import org.thingsboard.mqtt.broker.lightweight.service.subscription.SubscriptionRegistry;
import org.thingsboard.mqtt.broker.lightweight.session.ClientSessionCtx;
import org.thingsboard.mqtt.broker.lightweight.session.ClientSessionRegistry;
import org.thingsboard.mqtt.broker.lightweight.session.DisconnectReasonType;
import org.thingsboard.mqtt.broker.lightweight.session.SessionState;
import org.thingsboard.mqtt.broker.lightweight.session.TopicAliasCtx;
import org.thingsboard.mqtt.broker.lightweight.util.MqttPropertiesUtil;
import org.thingsboard.mqtt.broker.lightweight.util.MqttReasonCodeResolver;

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
    private final RetainedMsgService retainedMsgService;
    private final LastWillService lastWillService;
    private final MsgDispatcherService msgDispatcherService;
    private final LightweightAuthService authService;
    private final AuthorizationRuleService authorizationRuleService;
    private final MeterRegistry meterRegistry;
    private final Mqtt5Configuration mqtt5Config;

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
            case PUBLISH -> processPublish(ctx, (MqttPublishMessage) msg);
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

    /**
     * Sends a message to the cached client actor for this session. Returns silently if no session
     * has been initialized (i.e., before CONNECT). Centralizes the guard + tell pattern that
     * would otherwise be duplicated at every MQTT packet handler.
     */
    private void tellActor(TbActorMsg msg) {
        if (sessionCtx == null) {
            return;
        }
        actorSystem.tell(sessionCtx.getClientActorId(), msg);
    }

    private void processPublish(ChannelHandlerContext ctx, MqttPublishMessage mqttPublishMessage) {
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

        // MQTT 5.0: Extract properties and resolve topic alias
        MqttProperties properties = MqttProperties.NO_PROPERTIES;
        if (sessionCtx.getMqttVersion() == MqttVersion.MQTT_5) {
            MqttProperties inboundProps = mqttPublishMessage.variableHeader().properties();
            if (inboundProps != null && inboundProps != MqttProperties.NO_PROPERTIES) {
                // Copy properties for forwarding before ByteBuf release
                properties = MqttPropertiesUtil.copyPublishPropertiesToDeliver(inboundProps);
            }

            // Topic alias resolution — per D-12, D-13
            int topicAlias = MqttPropertiesUtil.getTopicAlias(mqttPublishMessage.variableHeader().properties());
            if (topicAlias > 0) {
                try {
                    String resolved = sessionCtx.getTopicAliasCtx().getTopicNameByAlias(topicName, topicAlias);
                    if (resolved != null) {
                        topicName = resolved;
                    } else if (topicName == null || topicName.isEmpty()) {
                        // Alias not found and no topic name — protocol error
                        log.warn("[{}] Topic alias {} has no mapping and topic name is empty",
                                sessionCtx.getClientId(), topicAlias);
                        disconnect(ctx, DisconnectReasonType.ON_PROTOCOL_ERROR,
                                "Topic alias with no mapping");
                        return;
                    }
                } catch (ProtocolViolationException e) {
                    // Topic alias validation failed (alias=0, exceeds max, or unknown mapping)
                    log.warn("[{}] Invalid topic alias: {}", sessionCtx.getClientId(), e.getMessage());
                    disconnect(ctx, DisconnectReasonType.ON_PROTOCOL_ERROR, e.getMessage());
                    return;
                }
            }
        }

        tellActor(new MqttPublishMsg(topicName, qos, payloadBytes, retain, dup, packetId, properties));
    }

    private void processSubscribe(MqttSubscribeMessage mqttSubscribeMessage) {
        tellActor(new MqttSubscribeMsg(mqttSubscribeMessage));
    }

    private void processUnsubscribe(MqttUnsubscribeMessage mqttUnsubscribeMessage) {
        tellActor(new MqttUnsubscribeMsg(mqttUnsubscribeMessage));
    }

    private void processPubAck(MqttMessage msg) {
        tellActor(new MqttPubAckMsg(((MqttMessageIdVariableHeader) msg.variableHeader()).messageId()));
    }

    private void processPubRec(MqttMessage msg) {
        tellActor(new MqttPubRecMsg(((MqttMessageIdVariableHeader) msg.variableHeader()).messageId()));
    }

    private void processPubRel(MqttMessage msg) {
        tellActor(new MqttPubRelMsg(((MqttMessageIdVariableHeader) msg.variableHeader()).messageId()));
    }

    private void processPubComp(MqttMessage msg) {
        tellActor(new MqttPubCompMsg(((MqttMessageIdVariableHeader) msg.variableHeader()).messageId()));
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

        // MQTT version detection — per PROTO-08
        int versionLevel = connectMsg.variableHeader().version();
        MqttVersion mqttVersion = (versionLevel == MqttVersion.MQTT_5.protocolLevel())
                ? MqttVersion.MQTT_5 : MqttVersion.MQTT_3_1_1;
        sessionCtx.setMqttVersion(mqttVersion);

        // Topic alias context — per D-12, D-13
        // Two independent limits:
        //   inboundMax  = broker's CONNACK TopicAliasMaximum — how many aliases the CLIENT may use
        //                 when publishing to the broker. We advertise mqtt5Config.getTopicAliasMax().
        //   outboundMax = client's CONNECT TopicAliasMaximum — how many aliases the BROKER may use
        //                 when publishing to the client. Default 0 = client did not opt-in.
        // MQTT 3.1.1 clients never use aliases (disabled singleton has inboundMax=0, outboundMax=0).
        TopicAliasCtx aliasCtx;
        if (mqttVersion == MqttVersion.MQTT_5) {
            MqttProperties connectProps = connectMsg.variableHeader().properties();
            int clientTopicAliasMax = MqttPropertiesUtil.getTopicAliasMaxFromConnect(connectProps);
            // Inbound: the broker advertises topicAliasMax in CONNACK, so the client may use aliases
            int inboundMax = mqtt5Config.getTopicAliasMax();
            // Outbound: respect client's limit (min of what client allows vs what broker wants to use)
            int outboundMax = clientTopicAliasMax > 0
                    ? Math.min(clientTopicAliasMax, mqtt5Config.getTopicAliasMax()) : 0;
            aliasCtx = new TopicAliasCtx(inboundMax, outboundMax);
        } else {
            aliasCtx = TopicAliasCtx.DISABLED_TOPIC_ALIASES;
        }
        sessionCtx.setTopicAliasCtx(aliasCtx);

        // Receive Maximum — per D-04
        if (mqttVersion == MqttVersion.MQTT_5) {
            MqttProperties connectProps5 = connectMsg.variableHeader().properties();
            int clientReceiveMax = MqttPropertiesUtil.getReceiveMaxFromConnect(connectProps5);
            sessionCtx.setReceiveMaximum(Math.min(clientReceiveMax, mqtt5Config.getReceiveMaximum()));
        }

        // Extract SslHandler for mTLS/X.509 client certificate authentication (null for plain TCP)
        SslHandler sslHandler = (SslHandler) ctx.pipeline().get("ssl");

        TbTypeActorId actorId = new TbTypeActorId("client", clientId);
        sessionCtx.setClientActorId(actorId);
        actorSystem.createRootActor(CLIENT_DISPATCHER, new ClientActorCreator(
                clientId, sessionRegistry, messageGenerator, mqttConfig, subscriptionRegistry,
                retainedMsgService, lastWillService, msgDispatcherService,
                authService, authorizationRuleService, meterRegistry, mqtt5Config));

        actorSystem.tell(actorId, new SessionInitMsg(sessionCtx));
        actorSystem.tell(actorId, new MqttConnectMsg(connectMsg, sessionCtx, sslHandler));

        connected = true;
    }

    private void processDisconnect(ChannelHandlerContext ctx) {
        if (sessionCtx != null) {
            // Per D-03: Ignore Session Expiry Interval in DISCONNECT packets
            // Always clean up immediately regardless of any properties
            tellActor(new MqttDisconnectMsg(DisconnectReasonType.ON_DISCONNECT_MSG, "Client disconnected"));
        } else {
            ctx.close();
        }
    }

    private void processPing() {
        tellActor(PingMsg.INSTANCE);
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
            TbTypeActorId actorId = sessionCtx.getClientActorId();
            try {
                actorSystem.tell(actorId, new SessionCloseMsg(DisconnectReasonType.ON_CHANNEL_CLOSED));
            } catch (TbActorNotRegisteredException e) {
                // Actor was already stopped (e.g., displaced by client takeover).
                // Channel close notification is expected and can be safely ignored.
                log.debug("[{}] Actor no longer registered on channel close — already cleaned up", sessionCtx.getClientId());
            }
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        String clientId = sessionCtx != null ? sessionCtx.getClientId() : "unknown";
        if (cause instanceof java.io.IOException || cause instanceof java.nio.channels.ClosedChannelException) {
            // Common: client closed the socket abruptly. Log at debug — the disconnect is
            // already handled by channelInactive; nothing actionable for operators.
            log.debug("[{}] Channel closed by remote: {}", clientId, cause.getMessage());
        } else {
            log.warn("[{}] Exception in MQTT session handler: {}", clientId, cause.getMessage(), cause);
        }
        disconnect(ctx, DisconnectReasonType.ON_ERROR, cause.getMessage());
    }

    /**
     * Closes the channel and marks the session as disconnecting.
     * For MQTT 5.0 clients, sends a DISCONNECT packet with reason code before closing.
     */
    private void disconnect(ChannelHandlerContext ctx, DisconnectReasonType reasonType, String reason) {
        log.debug("[{}] Disconnecting: {} — {}",
                sessionCtx != null ? sessionCtx.getClientId() : "unknown", reasonType, reason);
        if (sessionCtx != null) {
            sessionCtx.setState(SessionState.DISCONNECTING);

            // For MQTT 5.0: send DISCONNECT with reason code before closing (broker-initiated)
            if (sessionCtx.getMqttVersion() == MqttVersion.MQTT_5) {
                MqttReasonCodes.Disconnect reasonCode = MqttReasonCodeResolver.disconnectReasonFor(reasonType);
                try {
                    ctx.writeAndFlush(messageGenerator.createDisconnect(reasonCode));
                } catch (Exception e) {
                    log.debug("[{}] Failed to send DISCONNECT to 5.0 client: {}",
                            sessionCtx.getClientId(), e.getMessage());
                }
            }
        }
        ctx.close();
    }

}
