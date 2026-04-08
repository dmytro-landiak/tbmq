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
package org.thingsboard.mqtt.broker.lightweight.session;

import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;
import lombok.Setter;
import org.thingsboard.mqtt.broker.lightweight.packet.PacketIdAllocator;
import org.thingsboard.mqtt.broker.lightweight.security.auth.AuthRulePatterns;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.PublishMsg;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-connection session context.
 *
 * <p>Each MQTT client connection gets a unique {@code ClientSessionCtx} instance.
 * The {@code sessionId} is a UUID generated per TCP connection (not per clientId),
 * so the same clientId can produce different session IDs across reconnects.
 *
 * <p>QoS tracking maps:
 * <ul>
 *   <li>{@code inboundQos2} — deduplication map for QoS 2 PUBLISH received from client.
 *       Key: packetId, Value: marker object (AWAITING_PUBREL). Prevents duplicate delivery.</li>
 *   <li>{@code outboundQos1} — tracks QoS 1 messages sent to client awaiting PUBACK.
 *       Key: packetId, Value: PublishMsg pending acknowledgment.</li>
 *   <li>{@code outboundQos2} — tracks QoS 2 messages sent to client awaiting PUBCOMP.
 *       Key: packetId, Value: PublishMsg pending completion.</li>
 * </ul>
 */
@Getter
@Setter
public class ClientSessionCtx {

    /** Sentinel object for inboundQos2 map — marks that PUBREC has been sent, waiting for PUBREL. */
    public static final Object AWAITING_PUBREL = new Object();

    /** Unique identifier for this connection instance (not the clientId). */
    private final UUID sessionId;

    /** The Netty channel for writing responses back to the client. */
    private final ChannelHandlerContext channel;

    /** MQTT clientId from the CONNECT packet. Set after CONNECT is processed. */
    private volatile String clientId;

    /** Current lifecycle state of the session. */
    private volatile SessionState state;

    /** Clean session flag from the CONNECT packet. */
    private volatile boolean cleanSession;

    /** Keep-alive interval in seconds from the CONNECT packet (0 = disabled). */
    private volatile int keepAliveSeconds;

    /**
     * QoS 2 deduplication map for inbound PUBLISH messages.
     * Key: packetId from client PUBLISH, Value: {@link #AWAITING_PUBREL} marker.
     */
    private final ConcurrentHashMap<Integer, Object> inboundQos2;

    /**
     * QoS 2 outbound tracking map — messages sent to client awaiting PUBCOMP.
     * Key: packetId allocated by this broker, Value: PublishMsg awaiting PUBCOMP.
     */
    private final ConcurrentHashMap<Integer, PublishMsg> outboundQos2;

    /**
     * QoS 1 outbound tracking map — messages sent to client awaiting PUBACK.
     * Key: packetId allocated by this broker, Value: PublishMsg awaiting PUBACK.
     */
    private final ConcurrentHashMap<Integer, PublishMsg> outboundQos1;

    /** Per-session packet ID allocator for outbound QoS 1/2 messages. */
    private final PacketIdAllocator packetIdAllocator;

    /**
     * Compiled ACL patterns for this session, set once after successful authentication.
     * Empty list means anonymous access — ACL checks are bypassed entirely.
     */
    private volatile List<AuthRulePatterns> authRulePatterns = Collections.emptyList();

    public ClientSessionCtx(UUID sessionId, ChannelHandlerContext channel) {
        this.sessionId = sessionId;
        this.channel = channel;
        this.state = SessionState.INITIALIZING;
        this.inboundQos2 = new ConcurrentHashMap<>();
        this.outboundQos2 = new ConcurrentHashMap<>();
        this.outboundQos1 = new ConcurrentHashMap<>();
        this.packetIdAllocator = new PacketIdAllocator();
    }

}
