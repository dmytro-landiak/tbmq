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

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.lightweight.config.NettyConfiguration;

/**
 * Netty TCP server bootstrap for the MQTT broker.
 *
 * <p>Implements {@link org.springframework.context.SmartLifecycle} at phase {@code 0} to ensure:
 * <ul>
 *   <li>Starts AFTER RocksDB (phase {@link Integer#MIN_VALUE})</li>
 *   <li>Stops BEFORE RocksDB — SmartLifecycle stops beans in descending phase order,
 *       so phase 0 stops before MIN_VALUE</li>
 * </ul>
 *
 * <p>Shared Netty lifecycle (Epoll/NIO detection, bind, shutdown, port discovery)
 * is provided by {@link AbstractServerBootstrap}.
 */
@Slf4j
@Service
public class MqttTcpServerBootstrap extends AbstractServerBootstrap {

    private final NettyConfiguration config;
    private final MqttChannelInitializer channelInitializer;

    public MqttTcpServerBootstrap(NettyConfiguration config, MqttChannelInitializer channelInitializer) {
        super(config);
        this.config = config;
        this.channelInitializer = channelInitializer;
    }

    @Override
    public int getPhase() {
        // Phase 0: starts after RocksDB (Integer.MIN_VALUE), stops before RocksDB.
        // SmartLifecycle stop order is reverse of start order (descending phase).
        return 0;
    }

    @Override
    protected int getPort() {
        return config.getPort();
    }

    @Override
    protected ChannelInitializer<SocketChannel> getChannelInitializer() {
        return channelInitializer;
    }

    @Override
    protected String getServerName() {
        return "MQTT TCP";
    }

}
