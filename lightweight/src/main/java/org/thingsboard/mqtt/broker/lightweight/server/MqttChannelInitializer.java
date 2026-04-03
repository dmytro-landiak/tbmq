package org.thingsboard.mqtt.broker.lightweight.server;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Netty channel initializer for the MQTT TCP server.
 *
 * <p>Configures the channel pipeline for each new client connection.
 * Phase 1 pipeline is intentionally minimal — MQTT codec and session handler
 * will be added in Phase 2.
 *
 * <p>Pipeline (Phase 1):
 * <ol>
 *   <li>{@link IdleStateHandler} — keep-alive placeholder (all timeouts disabled, 0 = no timeout)</li>
 *   <li>{@link ConnectionCountHandler} — connection tracking and limit enforcement (shared)</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqttChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final ConnectionCountHandler connectionCountHandler;

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                // Phase 2 will configure actual MQTT keep-alive timeouts here
                .addLast("idle", new IdleStateHandler(0, 0, 0))
                // Shared connection counter and limit enforcer
                .addLast("connectionCount", connectionCountHandler);
        // Phase 2 will add: MqttDecoder, MqttEncoder, MqttSessionHandler
    }

}
