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
package org.thingsboard.mqtt.broker.lightweight.server.ws;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.lightweight.config.NettyConfiguration;
import org.thingsboard.mqtt.broker.lightweight.config.WsConfiguration;
import org.thingsboard.mqtt.broker.lightweight.server.AbstractServerBootstrap;

/**
 * Netty WebSocket (WS) server bootstrap for the MQTT broker.
 *
 * <p>Implements {@link org.springframework.context.SmartLifecycle} at phase {@code 2} to ensure:
 * <ul>
 *   <li>Starts AFTER TCP (phase 0), TLS (phase 1)</li>
 *   <li>Stops BEFORE TLS and TCP — SmartLifecycle stops in descending phase order</li>
 * </ul>
 *
 * <p>The WS listener is enabled by default ({@code tbmq.ws.enabled=true}) and starts on port 8084.
 *
 * <p>Shared Netty lifecycle (Epoll/NIO detection, bind, shutdown, port discovery)
 * is provided by {@link AbstractServerBootstrap}.
 */
@Slf4j
@Service
public class MqttWsServerBootstrap extends AbstractServerBootstrap {

    private final WsConfiguration wsConfig;
    private final MqttWsChannelInitializer channelInitializer;

    public MqttWsServerBootstrap(NettyConfiguration nettyConfig,
                                 WsConfiguration wsConfig,
                                 MqttWsChannelInitializer channelInitializer) {
        super(nettyConfig);
        this.wsConfig = wsConfig;
        this.channelInitializer = channelInitializer;
    }

    @Override
    public int getPhase() {
        // Phase 2: starts after TCP (0) and TLS (1). Stops before them.
        return 2;
    }

    @Override
    public boolean isAutoStartup() {
        return wsConfig.isEnabled();
    }

    @Override
    public void start() {
        if (!wsConfig.isEnabled()) {
            log.info("WebSocket listener is disabled (tbmq.ws.enabled=false), skipping startup");
            return;
        }
        super.start();
    }

    @Override
    protected int getPort() {
        return wsConfig.getPort();
    }

    @Override
    protected ChannelInitializer<SocketChannel> getChannelInitializer() {
        return channelInitializer;
    }

    @Override
    protected String getServerName() {
        return "MQTT WS";
    }

}
