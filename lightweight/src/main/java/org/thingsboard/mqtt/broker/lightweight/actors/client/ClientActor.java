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
package org.thingsboard.mqtt.broker.lightweight.actors.client;

import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.mqtt.broker.lightweight.actors.AbstractTbActor;
import org.thingsboard.mqtt.broker.lightweight.actors.MsgType;
import org.thingsboard.mqtt.broker.lightweight.actors.ProcessFailureStrategy;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorCtx;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorException;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorMsg;
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
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.PublishMsg;
import org.thingsboard.mqtt.broker.lightweight.service.subscription.Subscription;
import org.thingsboard.mqtt.broker.lightweight.service.subscription.SubscriptionRegistry;
import org.thingsboard.mqtt.broker.lightweight.session.ClientSessionCtx;
import org.thingsboard.mqtt.broker.lightweight.session.ClientSessionRegistry;
import org.thingsboard.mqtt.broker.lightweight.session.SessionState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Per-client actor that processes all MQTT messages for a single client connection.
 *
 * <p>One actor exists per connected MQTT clientId. Messages are processed sequentially,
 * ensuring thread safety without locks.
 *
 * <p>Message handling:
 * <ul>
 *   <li>SESSION_INIT_MSG — stores the session context</li>
 *   <li>CONNECT_MSG — validates, registers session, sends CONNACK, configures keep-alive</li>
 *   <li>DISCONNECT_MSG — removes session, closes channel, destroys actor</li>
 *   <li>PING_MSG — writes PINGRESP back to client</li>
 * </ul>
 *
 * <p>CRITICAL: Never block the actor thread. All channel writes use fire-and-forget
 * {@code writeAndFlush()} — no {@code CompletableFuture.get()} or blocking waits.
 */
@Slf4j
@RequiredArgsConstructor
public class ClientActor extends AbstractTbActor {

    private final ClientSessionRegistry sessionRegistry;
    private final MqttMessageGenerator messageGenerator;
    private final MqttConfiguration mqttConfig;
    private final SubscriptionRegistry subscriptionRegistry;

    /** Session context set on SESSION_INIT_MSG. */
    private ClientSessionCtx sessionCtx;

    /** Resolved clientId set on SESSION_INIT_MSG. */
    private String clientId;

    @Override
    public void init(TbActorCtx ctx) throws TbActorException {
        super.init(ctx);
        log.debug("ClientActor initialized: {}", ctx.getSelf());
    }

    @Override
    public void process(TbActorMsg msg) {
        MsgType msgType = msg.getMsgType();
        switch (msgType) {
            case SESSION_INIT_MSG -> processSessionInit((SessionInitMsg) msg);
            case CONNECT_MSG -> processConnect((MqttConnectMsg) msg);
            case DISCONNECT_MSG -> processDisconnect(msg);
            case PING_MSG -> processPing();
            case SUBSCRIBE_MSG -> processSubscribe((MqttSubscribeMsg) msg);
            case UNSUBSCRIBE_MSG -> processUnsubscribe((MqttUnsubscribeMsg) msg);
            case PUBLISH_MSG -> processPublish((MqttPublishMsg) msg);
            case PUBACK_MSG -> processPubAck((MqttPubAckMsg) msg);
            case PUBREC_MSG -> processPubRec((MqttPubRecMsg) msg);
            case PUBREL_MSG -> processPubRel((MqttPubRelMsg) msg);
            case PUBCOMP_MSG -> processPubComp((MqttPubCompMsg) msg);
            default -> log.warn("[{}] Unhandled message type: {}", clientId, msgType);
        }
    }

    private void processSessionInit(SessionInitMsg msg) {
        this.sessionCtx = msg.getSessionCtx();
        this.clientId = sessionCtx.getClientId();
        log.debug("[{}] Session initialized", clientId);
    }

    private void processConnect(MqttConnectMsg msg) {
        MqttConnectMessage connectMessage = msg.getConnectMessage();

        boolean cleanSession = connectMessage.variableHeader().isCleanSession();
        int keepAliveSeconds = connectMessage.variableHeader().keepAliveTimeSeconds();
        boolean willFlag = connectMessage.variableHeader().isWillFlag();

        // Cap keep-alive to broker maximum
        if (keepAliveSeconds > mqttConfig.getKeepAliveMax()) {
            log.debug("[{}] Capping keep-alive from {}s to {}s (broker max)",
                    clientId, keepAliveSeconds, mqttConfig.getKeepAliveMax());
            keepAliveSeconds = mqttConfig.getKeepAliveMax();
        }

        // Per D-10: always behave as Clean Session=1 in R1
        sessionCtx.setCleanSession(true);
        sessionCtx.setKeepAliveSeconds(keepAliveSeconds);

        // LWT is stubbed for Plan 05
        if (willFlag) {
            log.debug("[{}] LWT configured — will be processed in Plan 05", clientId);
        }

        // Register session — returns old session if client takeover (Plan 05)
        ClientSessionCtx oldSession = sessionRegistry.registerSession(clientId, sessionCtx);
        if (oldSession != null) {
            log.debug("[{}] Client takeover — closing old session (full implementation in Plan 05)", clientId);
            oldSession.getChannel().close();
        }

        // Send CONNACK — sessionPresent always false in R1 (clean session only)
        sessionCtx.getChannel().writeAndFlush(
                messageGenerator.createConnAck(MqttConnectReturnCode.CONNECTION_ACCEPTED, false));

        // Configure keep-alive idle timeout: replace the placeholder IdleStateHandler
        final int keepAliveFinal = keepAliveSeconds;
        if (keepAliveFinal > 0) {
            int timeoutSeconds = keepAliveFinal + (keepAliveFinal / 2); // 1.5x per spec 3.1.2.10
            sessionCtx.getChannel().channel().eventLoop().execute(() ->
                    sessionCtx.getChannel().pipeline().replace("idle", "idle",
                            new IdleStateHandler(timeoutSeconds, 0, 0)));
            log.debug("[{}] Keep-alive set to {}s (timeout: {}s)", clientId, keepAliveFinal, timeoutSeconds);
        }

        sessionCtx.setState(SessionState.CONNECTED);
        log.info("[{}] Client connected (cleanSession={}, keepAlive={}s)", clientId, cleanSession, keepAliveFinal);
    }

    private void processDisconnect(TbActorMsg msg) {
        boolean channelAlreadyClosed = (msg instanceof SessionCloseMsg);

        if (sessionCtx == null) {
            log.warn("[{}] Disconnect requested but no session context", clientId);
            return;
        }

        if (sessionCtx.getState() == SessionState.DISCONNECTED) {
            return; // already disconnected
        }

        sessionCtx.setState(SessionState.DISCONNECTING);

        // LWT is stubbed for Plan 05
        boolean allowsLwt = (msg instanceof MqttDisconnectMsg disconnectMsg)
                ? disconnectMsg.getReasonType().allowsLastWillOnDisconnect()
                : (msg instanceof SessionCloseMsg closeMsg) && closeMsg.getReasonType().allowsLastWillOnDisconnect();

        if (allowsLwt) {
            log.debug("[{}] LWT would fire here — will be implemented in Plan 05", clientId);
        }

        subscriptionRegistry.removeAllSubscriptions(clientId);
        sessionCtx.getInboundQos2().clear();
        sessionCtx.getOutboundQos1().clear();
        sessionCtx.getOutboundQos2().clear();
        sessionCtx.getPacketIdAllocator().releaseAll();

        sessionRegistry.removeSession(clientId);

        if (!channelAlreadyClosed) {
            sessionCtx.getChannel().close();
        }

        sessionCtx.setState(SessionState.DISCONNECTED);

        if (ctx != null) {
            ctx.stop(ctx.getSelf());
        }

        log.info("[{}] Client disconnected", clientId);
    }

    private void processSubscribe(MqttSubscribeMsg msg) {
        int packetId = msg.getMqttSubscribeMessage().variableHeader().messageId();
        List<MqttTopicSubscription> topicSubs = msg.getMqttSubscribeMessage().payload().topicSubscriptions();
        List<Integer> grantedQosList = new ArrayList<>();

        for (MqttTopicSubscription topicSub : topicSubs) {
            String topicFilter = topicSub.topicName();
            int requestedQos = topicSub.qualityOfService().value();
            int grantedQos = Math.min(requestedQos, 2); // broker supports up to QoS 2
            grantedQosList.add(grantedQos);

            Subscription subscription = new Subscription(clientId, grantedQos, sessionCtx);
            subscriptionRegistry.subscribe(topicFilter, subscription);
            log.debug("[{}] Subscribed to '{}' with QoS {}", clientId, topicFilter, grantedQos);

            // Deliver retained messages for this topic (STUBBED for Plan 05)
        }

        sessionCtx.getChannel().writeAndFlush(
                messageGenerator.createSubAck(packetId, grantedQosList));
    }

    private void processUnsubscribe(MqttUnsubscribeMsg msg) {
        int packetId = msg.getMqttUnsubscribeMessage().variableHeader().messageId();
        List<String> topics = msg.getMqttUnsubscribeMessage().payload().topics();

        for (String topic : topics) {
            subscriptionRegistry.unsubscribe(topic, clientId);
            log.debug("[{}] Unsubscribed from '{}'", clientId, topic);
        }

        sessionCtx.getChannel().writeAndFlush(
                messageGenerator.createUnsubAck(packetId));
    }

    private void processPublish(MqttPublishMsg msg) {
        String topicName = msg.getTopicName();
        int publishQos = msg.getQos();
        byte[] payload = msg.getPayload();
        boolean retain = msg.isRetain();
        int inboundPacketId = msg.getPacketId();

        if (publishQos == 1) {
            // QoS 1: Send PUBACK to publisher, then deliver
            sessionCtx.getChannel().writeAndFlush(messageGenerator.createPubAck(inboundPacketId));
        } else if (publishQos == 2) {
            // QoS 2 deduplication check (Pitfall 2 from RESEARCH)
            if (sessionCtx.getInboundQos2().containsKey(inboundPacketId)) {
                // DUP retransmit: resend PUBREC, do NOT deliver again
                sessionCtx.getChannel().writeAndFlush(messageGenerator.createPubRec(inboundPacketId));
                return;
            }
            // Store the PublishMsg so it can be delivered when PUBREL arrives
            PublishMsg publishMsg = PublishMsg.builder()
                    .topicName(topicName)
                    .qos(2)
                    .payload(payload)
                    .retain(retain)
                    .dup(false)
                    .packetId(inboundPacketId)
                    .build();
            sessionCtx.getInboundQos2().put(inboundPacketId, publishMsg);
            sessionCtx.getChannel().writeAndFlush(messageGenerator.createPubRec(inboundPacketId));
            // Do NOT deliver yet — wait for PUBREL
            return;
        }

        // Retained message storage (STUBBED for Plan 05)

        // Deliver to subscribers (QoS 0 and QoS 1 deliver immediately)
        deliverToSubscribers(topicName, publishQos, payload);
    }

    private void processPubAck(MqttPubAckMsg msg) {
        int packetId = msg.getPacketId();
        sessionCtx.getOutboundQos1().remove(packetId);
        sessionCtx.getPacketIdAllocator().releasePacketId(packetId);
        log.trace("[{}] PUBACK received for packetId={}, QoS 1 flow complete", clientId, packetId);
    }

    private void processPubRec(MqttPubRecMsg msg) {
        int packetId = msg.getPacketId();
        // Send PUBREL to complete QoS 2 outbound handshake
        sessionCtx.getChannel().writeAndFlush(messageGenerator.createPubRel(packetId));
        log.trace("[{}] PUBREC received for packetId={}, sent PUBREL", clientId, packetId);
    }

    private void processPubRel(MqttPubRelMsg msg) {
        int packetId = msg.getPacketId();
        // Retrieve stored PublishMsg from inbound QoS 2 map
        Object stored = sessionCtx.getInboundQos2().remove(packetId);
        if (stored instanceof PublishMsg publishMsg) {
            // Deliver to subscribers now (exactly once)
            deliverToSubscribers(publishMsg.getTopicName(), 2, publishMsg.getPayload());
        } else {
            log.warn("[{}] PUBREL received for unknown packetId={}", clientId, packetId);
        }
        sessionCtx.getChannel().writeAndFlush(messageGenerator.createPubComp(packetId));
    }

    private void processPubComp(MqttPubCompMsg msg) {
        int packetId = msg.getPacketId();
        sessionCtx.getOutboundQos2().remove(packetId);
        sessionCtx.getPacketIdAllocator().releasePacketId(packetId);
        log.trace("[{}] PUBCOMP received for packetId={}, QoS 2 flow complete", clientId, packetId);
    }

    /**
     * Delivers a message to all exact-match subscribers for the given topic.
     *
     * <p>Per D-04, delivery is inline (direct channel write) without a dispatch queue.
     * QoS is downgraded to min(publishQos, subscriptionQos) per MQTT spec.
     */
    private void deliverToSubscribers(String topicName, int publishQos, byte[] payload) {
        Set<Subscription> subs = subscriptionRegistry.getSubscriptions(topicName);
        for (Subscription sub : subs) {
            ClientSessionCtx subscriberCtx = sub.getSessionCtx();
            if (subscriberCtx.getState() != SessionState.CONNECTED) {
                continue;
            }
            int deliveryQos = Math.min(publishQos, sub.getQos()); // QoS downgrade per spec

            if (deliveryQos == 0) {
                // QoS 0: fire and forget
                subscriberCtx.getChannel().writeAndFlush(
                        messageGenerator.createPublish(topicName, 0, payload, false, false, 0));
            } else if (deliveryQos == 1) {
                // QoS 1: assign packet ID, track in outbound map
                int outPktId = subscriberCtx.getPacketIdAllocator().nextPacketId();
                PublishMsg outMsg = PublishMsg.builder()
                        .topicName(topicName).qos(1).payload(payload).retain(false).dup(false).packetId(outPktId)
                        .build();
                subscriberCtx.getOutboundQos1().put(outPktId, outMsg);
                subscriberCtx.getChannel().writeAndFlush(
                        messageGenerator.createPublish(topicName, 1, payload, false, false, outPktId));
            } else { // deliveryQos == 2
                int outPktId = subscriberCtx.getPacketIdAllocator().nextPacketId();
                PublishMsg outMsg = PublishMsg.builder()
                        .topicName(topicName).qos(2).payload(payload).retain(false).dup(false).packetId(outPktId)
                        .build();
                subscriberCtx.getOutboundQos2().put(outPktId, outMsg);
                subscriberCtx.getChannel().writeAndFlush(
                        messageGenerator.createPublish(topicName, 2, payload, false, false, outPktId));
            }
        }
    }

    private void processPing() {
        if (sessionCtx != null) {
            sessionCtx.getChannel().writeAndFlush(messageGenerator.createPingResp());
            log.trace("[{}] PINGREQ -> PINGRESP", clientId);
        }
    }

    @Override
    public void destroy() throws TbActorException {
        log.debug("[{}] ClientActor destroyed", clientId);
    }

    @Override
    public ProcessFailureStrategy onProcessFailure(Throwable t) {
        log.error("[{}] Error processing message: {}", clientId, t.getMessage(), t);
        return ProcessFailureStrategy.resume();
    }

}
