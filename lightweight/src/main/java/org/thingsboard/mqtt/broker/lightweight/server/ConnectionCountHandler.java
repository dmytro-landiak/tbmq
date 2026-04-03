package org.thingsboard.mqtt.broker.lightweight.server;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thingsboard.mqtt.broker.lightweight.config.NettyConfiguration;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A sharable Netty channel handler that tracks the number of active MQTT connections
 * and enforces a configurable maximum connection limit.
 *
 * <p>This handler is {@link ChannelHandler.Sharable} because it uses thread-safe
 * {@link AtomicInteger} for connection counting and has no per-channel state.
 *
 * <p>Metrics: registers a Prometheus gauge {@code mqtt.connections.active} via
 * Micrometer's {@link Gauge} builder.
 *
 * <p>Connection limit: if the current count exceeds {@code NettyConfiguration#maxConnections},
 * the new channel is closed immediately with a warning log.
 */
@Slf4j
@Component
@ChannelHandler.Sharable
@RequiredArgsConstructor
public class ConnectionCountHandler extends ChannelInboundHandlerAdapter {

    private final NettyConfiguration config;
    private final MeterRegistry meterRegistry;

    private final AtomicInteger connectionCount = new AtomicInteger(0);

    @PostConstruct
    public void initMetrics() {
        Gauge.builder("mqtt.connections.active", connectionCount, AtomicInteger::get)
                .description("Current number of active MQTT connections")
                .register(meterRegistry);
        log.info("ConnectionCountHandler initialized: max connections = {}", config.getMaxConnections());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        int current = connectionCount.incrementAndGet();
        if (current > config.getMaxConnections()) {
            connectionCount.decrementAndGet();
            log.warn("Max connections limit reached ({}), rejecting connection from {}",
                    config.getMaxConnections(), ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        connectionCount.decrementAndGet();
        super.channelInactive(ctx);
    }

    /**
     * Returns the current number of active connections.
     * Exposed for testing and monitoring purposes.
     */
    public int getConnectionCount() {
        return connectionCount.get();
    }

}
