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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.thingsboard.mqtt.broker.lightweight.config.NettyConfiguration;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for all MQTT Netty server bootstraps (TCP, TLS, WS, WSS).
 *
 * <p>Encapsulates the common Netty server lifecycle: Epoll/NIO detection, boss/worker
 * group creation, ServerBootstrap configuration, bind, and graceful shutdown.
 *
 * <p>Subclasses must provide:
 * <ul>
 *   <li>{@link #getPort()} — the port to bind to</li>
 *   <li>{@link #getChannelInitializer()} — the Netty pipeline initializer</li>
 *   <li>{@link #getServerName()} — a human-readable name for log messages</li>
 * </ul>
 *
 * <p>SmartLifecycle methods {@code getPhase()} and {@code isAutoStartup()} are NOT overridden
 * here — each concrete subclass provides its own phase and auto-startup configuration.
 */
@Slf4j
public abstract class AbstractServerBootstrap implements SmartLifecycle {

    private final NettyConfiguration nettyConfig;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;

    protected AbstractServerBootstrap(NettyConfiguration nettyConfig) {
        this.nettyConfig = nettyConfig;
    }

    /**
     * Returns the port this server should bind to.
     */
    protected abstract int getPort();

    /**
     * Returns the Netty channel initializer for this transport.
     */
    protected abstract ChannelInitializer<SocketChannel> getChannelInitializer();

    /**
     * Returns a human-readable name for log messages (e.g., "MQTT TCP", "MQTT TLS").
     */
    protected abstract String getServerName();

    @Override
    public void start() {
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
                    .childHandler(getChannelInitializer());

            serverChannel = bootstrap.bind(getPort()).sync().channel();
            running = true;

            log.info("{} listener started on port {} (transport: {})",
                    getServerName(), getLocalPort(), transport);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while binding " + getServerName() + " server on port " + getPort(), e);
        }
    }

    @Override
    public void stop() {
        log.info("Stopping {} listener...", getServerName());

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
        log.info("{} listener stopped", getServerName());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the actual local port the server is bound to.
     * When configured with port=0, returns the OS-assigned random port.
     * Returns -1 if the server is not running.
     */
    public int getLocalPort() {
        if (serverChannel == null) {
            return -1;
        }
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

}
