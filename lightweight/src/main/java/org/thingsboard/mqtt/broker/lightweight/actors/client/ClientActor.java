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
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.PingMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.SessionCloseMsg;
import org.thingsboard.mqtt.broker.lightweight.actors.client.msg.SessionInitMsg;
import org.thingsboard.mqtt.broker.lightweight.config.MqttConfiguration;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.MqttMessageGenerator;
import org.thingsboard.mqtt.broker.lightweight.session.ClientSessionCtx;
import org.thingsboard.mqtt.broker.lightweight.session.ClientSessionRegistry;
import org.thingsboard.mqtt.broker.lightweight.session.SessionState;

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
