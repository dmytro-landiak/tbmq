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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.lightweight.config.NettyConfiguration;
import org.thingsboard.mqtt.broker.lightweight.config.TlsConfiguration;
import org.thingsboard.mqtt.broker.lightweight.config.WssConfiguration;
import org.thingsboard.mqtt.broker.lightweight.server.AbstractServerBootstrap;

/**
 * Netty Secure WebSocket (WSS) server bootstrap for the MQTT broker.
 *
 * <p>Implements {@link org.springframework.context.SmartLifecycle} at phase {@code 3} to ensure:
 * <ul>
 *   <li>Starts AFTER TCP (0), TLS (1), and WS (2)</li>
 *   <li>Stops BEFORE WS, TLS, and TCP — SmartLifecycle stops in descending phase order</li>
 * </ul>
 *
 * <p>The WSS listener is disabled by default ({@code tbmq.wss.enabled=false}).
 * It only starts when BOTH {@code tbmq.wss.enabled=true} AND {@code tbmq.tls.enabled=true}
 * are set — WSS requires TLS certificates to be configured.
 *
 * <p>Shared Netty lifecycle (Epoll/NIO detection, bind, shutdown, port discovery)
 * is provided by {@link AbstractServerBootstrap}.
 */
@Slf4j
@Service
public class MqttWssServerBootstrap extends AbstractServerBootstrap {

    private final WssConfiguration wssConfig;
    private final TlsConfiguration tlsConfig;
    private final MqttWssChannelInitializer channelInitializer;

    public MqttWssServerBootstrap(NettyConfiguration nettyConfig,
                                  WssConfiguration wssConfig,
                                  TlsConfiguration tlsConfig,
                                  MqttWssChannelInitializer channelInitializer) {
        super(nettyConfig);
        this.wssConfig = wssConfig;
        this.tlsConfig = tlsConfig;
        this.channelInitializer = channelInitializer;
    }

    @Override
    public int getPhase() {
        // Phase 3: starts after TCP (0), TLS (1), WS (2). Stops before all.
        return 3;
    }

    @Override
    public boolean isAutoStartup() {
        // WSS requires BOTH wss.enabled=true AND tls.enabled=true (Pitfall 2 from RESEARCH.md)
        return wssConfig.isEnabled() && tlsConfig.isEnabled();
    }

    @Override
    public void start() {
        if (!wssConfig.isEnabled()) {
            log.info("Secure WebSocket listener is disabled (tbmq.wss.enabled=false), skipping startup");
            return;
        }
        if (!tlsConfig.isEnabled()) {
            log.warn("WSS is enabled but TLS is not configured (tbmq.tls.enabled=false). WSS listener cannot start without TLS certificates.");
            return;
        }
        super.start();
    }

    @Override
    protected int getPort() {
        return wssConfig.getPort();
    }

    @Override
    protected ChannelInitializer<SocketChannel> getChannelInitializer() {
        return channelInitializer;
    }

    @Override
    protected String getServerName() {
        return "MQTT WSS";
    }

}
