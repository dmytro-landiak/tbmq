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
package org.thingsboard.mqtt.broker.lightweight.server.wss;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thingsboard.mqtt.broker.lightweight.config.MqttConfiguration;
import org.thingsboard.mqtt.broker.lightweight.config.WssConfiguration;
import org.thingsboard.mqtt.broker.lightweight.server.ConnectionCountHandler;
import org.thingsboard.mqtt.broker.lightweight.server.MqttSessionHandlerFactory;
import org.thingsboard.mqtt.broker.lightweight.server.tls.MqttSslHandlerProvider;
import org.thingsboard.mqtt.broker.lightweight.server.wshandler.WsBinaryFrameHandler;
import org.thingsboard.mqtt.broker.lightweight.server.wshandler.WsByteBufEncoder;
import org.thingsboard.mqtt.broker.lightweight.server.wshandler.WsContinuationFrameHandler;
import org.thingsboard.mqtt.broker.lightweight.server.wshandler.WsTextFrameHandler;

/**
 * Netty channel initializer for the MQTT Secure WebSocket (WSS) server.
 *
 * <p>Identical to the WS channel initializer except the {@code "ssl"} handler is
 * prepended as the first pipeline stage, before everything else.
 *
 * <p>Pipeline order (per D-06, D-01):
 * <ol>
 *   <li>{@code "ssl"} — {@link io.netty.handler.ssl.SslHandler} (FIRST — decrypts inbound, encrypts outbound)</li>
 *   <li>{@code "idle"} — {@link IdleStateHandler} placeholder (all timeouts 0 until CONNECT)</li>
 *   <li>{@code "connectionCount"} — shared connection counter and limit enforcer</li>
 *   <li>{@link HttpServerCodec} — HTTP request/response codec for WS upgrade</li>
 *   <li>{@link HttpObjectAggregator} — aggregates HTTP chunks for WS handshake</li>
 *   <li>{@link WebSocketServerProtocolHandler} — handles WS upgrade; echoes Sec-WebSocket-Protocol</li>
 *   <li>{@link WsBinaryFrameHandler} — unwraps binary WS frames to ByteBuf</li>
 *   <li>{@link WsContinuationFrameHandler} — handles WS continuation frames</li>
 *   <li>{@link WsTextFrameHandler} — handles (and rejects) text WS frames</li>
 *   <li>{@link WsByteBufEncoder} — wraps outbound ByteBuf into binary WS frames</li>
 *   <li>{@code "decoder"} — MQTT frame decoder (new instance per channel)</li>
 *   <li>{@code "encoder"} — MQTT frame encoder (shared singleton)</li>
 *   <li>{@code "handler"} — per-session {@link org.thingsboard.mqtt.broker.lightweight.server.MqttSessionHandler}</li>
 * </ol>
 *
 * <p>CRITICAL (per D-01): Reuses {@link MqttSslHandlerProvider} from Phase 4 — no separate SSL
 * context for WSS.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqttWssChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final String WS_PATH = "/mqtt";

    private final MqttSslHandlerProvider sslHandlerProvider;
    private final ConnectionCountHandler connectionCountHandler;
    private final MqttSessionHandlerFactory sessionHandlerFactory;
    private final MqttConfiguration mqttConfig;
    private final WssConfiguration wssConfig;

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                // SSL MUST be first — decrypts inbound bytes before anything else sees them
                .addLast("ssl", sslHandlerProvider.createSslHandler(ch))
                // Keep-alive placeholder — all timeouts disabled until CONNECT is processed
                .addLast("idle", new IdleStateHandler(0, 0, 0))
                // Shared connection counter and limit enforcer
                .addLast("connectionCount", connectionCountHandler)
                // HTTP/WebSocket upgrade pipeline
                .addLast(new HttpServerCodec())
                .addLast(new HttpObjectAggregator(wssConfig.getMaxContentLength()))
                .addLast(new WebSocketServerProtocolHandler(WS_PATH, wssConfig.getSubProtocols()))
                // WebSocket frame handlers — decode WS frames to ByteBuf for MQTT decoder
                .addLast(new WsBinaryFrameHandler())
                .addLast(new WsContinuationFrameHandler())
                .addLast(new WsTextFrameHandler())
                // Encode outbound ByteBuf back into WS binary frames
                .addLast(new WsByteBufEncoder())
                // MQTT codec: decoder is NOT @Sharable — new instance per channel
                .addLast("decoder", new MqttDecoder(mqttConfig.getMaxPayloadSize(), mqttConfig.getMaxClientIdLength()))
                // MQTT encoder: @Sharable singleton
                .addLast("encoder", MqttEncoder.INSTANCE)
                // Per-channel session handler: NOT @Sharable — new instance per channel
                .addLast("handler", sessionHandlerFactory.create());
    }

}
