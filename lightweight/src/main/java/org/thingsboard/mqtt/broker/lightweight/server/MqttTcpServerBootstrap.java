package org.thingsboard.mqtt.broker.lightweight.server;

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

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Netty TCP server bootstrap for the MQTT broker.
 *
 * <p>Implements {@link SmartLifecycle} at phase {@code 0} to ensure:
 * <ul>
 *   <li>Starts AFTER RocksDB (phase {@link Integer#MIN_VALUE})</li>
 *   <li>Stops BEFORE RocksDB — SmartLifecycle stops beans in descending phase order,
 *       so phase 0 stops before MIN_VALUE</li>
 * </ul>
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
public class MqttTcpServerBootstrap implements SmartLifecycle {

    private final NettyConfiguration config;
    private final MqttChannelInitializer channelInitializer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;

    @Override
    public int getPhase() {
        // Phase 0: starts after RocksDB (Integer.MIN_VALUE), stops before RocksDB.
        // SmartLifecycle stop order is reverse of start order (descending phase).
        return 0;
    }

    @Override
    public void start() {
        boolean epollAvailable = Epoll.isAvailable();
        String transport = epollAvailable ? "epoll" : "nio";

        int workerThreadCount = config.getWorkerThreads() > 0
                ? config.getWorkerThreads()
                : Runtime.getRuntime().availableProcessors();

        if (epollAvailable) {
            bossGroup = new EpollEventLoopGroup(config.getBossThreads());
            workerGroup = new EpollEventLoopGroup(workerThreadCount);
        } else {
            bossGroup = new NioEventLoopGroup(config.getBossThreads());
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
                    .childHandler(channelInitializer);

            serverChannel = bootstrap.bind(config.getPort()).sync().channel();
            running = true;

            log.info("MQTT TCP listener started on port {} (transport: {})",
                    getLocalPort(), transport);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while binding MQTT TCP server on port " + config.getPort(), e);
        }
    }

    @Override
    public void stop() {
        log.info("Stopping MQTT TCP listener...");

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
        log.info("MQTT TCP listener stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the actual local port the server is bound to.
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
