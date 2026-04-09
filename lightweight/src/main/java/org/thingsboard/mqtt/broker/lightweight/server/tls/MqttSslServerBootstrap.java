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

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.lightweight.config.NettyConfiguration;
import org.thingsboard.mqtt.broker.lightweight.config.TlsConfiguration;
import org.thingsboard.mqtt.broker.lightweight.server.AbstractServerBootstrap;

/**
 * Netty TLS server bootstrap for the MQTT broker.
 *
 * <p>Implements {@link org.springframework.context.SmartLifecycle} at phase {@code 1} to ensure:
 * <ul>
 *   <li>Starts AFTER TCP bootstrap (phase {@code 0}) and RocksDB (phase {@link Integer#MIN_VALUE})</li>
 *   <li>Stops BEFORE TCP bootstrap — SmartLifecycle stops beans in descending phase order</li>
 * </ul>
 *
 * <p>The TLS listener only starts when {@code tbmq.tls.enabled=true}. It is disabled by default
 * so the broker can start without certificate configuration.
 *
 * <p>Shared Netty lifecycle (Epoll/NIO detection, bind, shutdown, port discovery)
 * is provided by {@link AbstractServerBootstrap}.
 */
@Slf4j
@Service
public class MqttSslServerBootstrap extends AbstractServerBootstrap {

    private final TlsConfiguration tlsConfig;
    private final MqttSslChannelInitializer sslChannelInitializer;

    public MqttSslServerBootstrap(TlsConfiguration tlsConfig, NettyConfiguration nettyConfig,
                                  MqttSslChannelInitializer sslChannelInitializer) {
        super(nettyConfig);
        this.tlsConfig = tlsConfig;
        this.sslChannelInitializer = sslChannelInitializer;
    }

    @Override
    public int getPhase() {
        // Phase 1: starts after TCP bootstrap (phase 0) and RocksDB (Integer.MIN_VALUE).
        // Stops before TCP bootstrap — SmartLifecycle stops in descending phase order.
        return 1;
    }

    @Override
    public boolean isAutoStartup() {
        return tlsConfig.isEnabled();
    }

    @Override
    public void start() {
        if (!tlsConfig.isEnabled()) {
            log.info("TLS listener is disabled (tbmq.tls.enabled=false), skipping startup");
            return;
        }
        super.start();
    }

    @Override
    protected int getPort() {
        return tlsConfig.getPort();
    }

    @Override
    protected ChannelInitializer<SocketChannel> getChannelInitializer() {
        return sslChannelInitializer;
    }

    @Override
    protected String getServerName() {
        return "MQTT TLS";
    }

}
