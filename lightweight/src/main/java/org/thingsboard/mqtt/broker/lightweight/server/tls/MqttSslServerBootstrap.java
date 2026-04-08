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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.lightweight.config.NettyConfiguration;
import org.thingsboard.mqtt.broker.lightweight.config.TlsConfiguration;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Netty TLS server bootstrap for the MQTT broker.
 *
 * <p>Implements {@link SmartLifecycle} at phase {@code 1} to ensure:
 * <ul>
 *   <li>Starts AFTER TCP bootstrap (phase {@code 0}) and RocksDB (phase {@link Integer#MIN_VALUE})</li>
 *   <li>Stops BEFORE TCP bootstrap — SmartLifecycle stops beans in descending phase order</li>
 * </ul>
 *
 * <p>The TLS listener only starts when {@code tbmq.tls.enabled=true}. It is disabled by default
 * so the broker can start without certificate configuration.
 *
 * <p>Transport detection: uses Epoll on Linux (if available) for better performance,
 * falls back to NIO on other platforms.
 *
 * <p>Port discovery: {@link #getLocalPort()} returns the actual bound port,
 * which is useful when {@code port=0} is configured for test isolation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MqttSslServerBootstrap implements SmartLifecycle {

    private final TlsConfiguration tlsConfig;
    private final NettyConfiguration nettyConfig;
    private final MqttSslChannelInitializer sslChannelInitializer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;

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

        boolean epollAvailable = Epoll.isAvailable();
        String transport = epollAvailable ? "epoll" : "nio";

        int workerThreadCount = nettyConfig.getWorkerThreads() > 0
                ? nettyConfig.getWorkerThreads()
                : Runtime.getRuntime().availableProcessors();

        if (epollAvailable) {
            bossGroup = new EpollEventLoopGroup(nettyConfig.getBossThreads());
            workerGroup = new EpollEventLoopGroup(workerThreadCount);
        } else {
            bossGroup = new NioEventLoopGroup(nettyConfig.getBossThreads());
            workerGroup = new NioEventLoopGroup(workerThreadCount);
        }

        Class<? extends io.netty.channel.ServerChannel> channelClass =
                epollAvailable ? EpollServerSocketChannel.class : NioServerSocketChannel.class;

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(channelClass)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .childHandler(sslChannelInitializer);

            serverChannel = bootstrap.bind(tlsConfig.getPort()).sync().channel();
            running = true;

            log.info("MQTT TLS listener started on port {} (transport: {})",
                    getLocalPort(), transport);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while binding MQTT TLS server on port " + tlsConfig.getPort(), e);
        }
    }

    @Override
    public void stop() {
        log.info("Stopping MQTT TLS listener...");

        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
            serverChannel = null;
        }

        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
            bossGroup = null;
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
            workerGroup = null;
        }

        running = false;
        log.info("MQTT TLS listener stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the actual local port the TLS server is bound to.
     * When configured with port=0, returns the OS-assigned random port.
     * Useful for test isolation.
     */
    public int getLocalPort() {
        if (serverChannel == null) {
            return -1;
        }
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

}
