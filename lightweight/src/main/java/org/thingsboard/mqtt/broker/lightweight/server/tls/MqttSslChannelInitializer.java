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
package org.thingsboard.mqtt.broker.lightweight.server.tls;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorSystem;
import org.thingsboard.mqtt.broker.lightweight.config.MqttConfiguration;
import org.thingsboard.mqtt.broker.lightweight.security.acl.AuthorizationRuleService;
import org.thingsboard.mqtt.broker.lightweight.security.auth.LightweightAuthService;
import org.thingsboard.mqtt.broker.lightweight.server.ConnectionCountHandler;
import org.thingsboard.mqtt.broker.lightweight.server.MqttSessionHandler;
import org.thingsboard.mqtt.broker.lightweight.service.dispatch.MsgDispatcherService;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.MqttMessageGenerator;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.retain.RetainedMsgService;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.will.LastWillService;
import org.thingsboard.mqtt.broker.lightweight.service.subscription.SubscriptionRegistry;
import org.thingsboard.mqtt.broker.lightweight.session.ClientSessionRegistry;

/**
 * Netty channel initializer for the MQTT TLS server.
 *
 * <p>Identical to the TCP channel initializer except the {@code "ssl"} handler is
 * prepended as the first pipeline stage, before the keep-alive idle handler.
 *
 * <p>Pipeline order:
 * <ol>
 *   <li>{@code "ssl"} — {@link io.netty.handler.ssl.SslHandler} (FIRST — decrypts inbound, encrypts outbound)</li>
 *   <li>{@code "idle"} — {@link IdleStateHandler} placeholder (0 = no timeout until CONNECT)</li>
 *   <li>{@code "connectionCount"} — connection tracking and limit enforcement</li>
 *   <li>{@code "decoder"} — MQTT frame decoder (new instance per channel)</li>
 *   <li>{@code "encoder"} — MQTT frame encoder (shared singleton)</li>
 *   <li>{@code "handler"} — per-session {@link MqttSessionHandler} (new instance per channel)</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqttSslChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final MqttSslHandlerProvider sslHandlerProvider;
    private final ConnectionCountHandler connectionCountHandler;
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

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                // SSL handler MUST be first — decrypts inbound bytes before MQTT decoder sees them
                .addLast("ssl", sslHandlerProvider.createSslHandler(ch))
                // Keep-alive placeholder — all timeouts disabled until CONNECT is processed
                .addLast("idle", new IdleStateHandler(0, 0, 0))
                // Shared connection counter and limit enforcer
                .addLast("connectionCount", connectionCountHandler)
                // MQTT codec: decoder is NOT @Sharable — new instance per channel
                .addLast("decoder", new MqttDecoder(mqttConfig.getMaxPayloadSize(), mqttConfig.getMaxClientIdLength()))
                // MQTT encoder: @Sharable singleton
                .addLast("encoder", MqttEncoder.INSTANCE)
                // Per-channel session handler: NOT @Sharable — new instance per channel
                .addLast("handler", new MqttSessionHandler(actorSystem, sessionRegistry, messageGenerator, mqttConfig, subscriptionRegistry,
                        retainedMsgService, lastWillService, msgDispatcherService,
                        authService, authorizationRuleService, meterRegistry));
    }

}
