# Phase 5: WebSocket Transport - Context

**Gathered:** 2026-04-09
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase adds MQTT over WebSocket (ws://) and secure WebSocket (wss://) transport to the broker. Browser-based MQTT clients (MQTT.js, Paho JavaScript) can connect via WebSocket and publish/subscribe using the same protocol layer as TCP clients. The broker responds with `Sec-WebSocket-Protocol: mqtt` in the WebSocket handshake for browser compatibility. WSS reuses the same TLS certificates as the MQTTS listener. As a refactoring side-effect, this phase extracts an abstract server bootstrap and a handler factory to eliminate duplication across the now-four transport bootstraps (TCP, TLS, WS, WSS).

</domain>

<decisions>
## Implementation Decisions

### WSS Certificate Configuration
- **D-01:** WSS reuses the same PEM certificate/key/truststore as the MQTTS listener (Phase 4's `TBMQ_TLS_*` env vars). No separate certificate configuration for WSS. This simplifies Docker setup — one volume mount and one set of env vars covers both MQTTS and WSS. The shared `MqttSslHandlerProvider` from Phase 4 creates `SslHandler` instances for both MQTTS and WSS channel initializers.

### Bootstrap Architecture
- **D-02:** Extract an `AbstractServerBootstrap` base class encapsulating shared Netty lifecycle: Epoll/NIO transport detection, boss/worker `EventLoopGroup` creation, `ServerBootstrap` configuration, bind, and graceful shutdown. Each concrete bootstrap (TCP, TLS, WS, WSS) provides only its configuration: port, `SmartLifecycle` phase, enabled flag, and channel initializer. This retroactively refactors the existing TCP and TLS bootstraps from Phases 1 and 4.
- **D-03:** SmartLifecycle phase ordering: TCP=0, TLS=1, WS=2, WSS=3. WS and WSS start after TCP and TLS; stop before them (descending phase order).

### WebSocket Subprotocol Negotiation
- **D-04:** Advertise both `mqttv3.1,mqtt` as supported WebSocket subprotocols from day one, matching TBMQ's default. The `mqtt` subprotocol represents both MQTT 3.1.1 and 5.0 per the OASIS standard. Browser clients (MQTT.js) default to requesting `mqtt`, so including it now avoids handshake failures and prevents breaking changes when Phase 6 adds MQTT 5.0.

### Channel Initializer Architecture
- **D-05:** Create a `MqttSessionHandlerFactory` that encapsulates all session handler dependencies (actor system, session registry, message generator, mqtt config, subscription registry, retained msg service, last will service, dispatcher, auth service, authorization rule service, meter registry) and produces new `MqttSessionHandler` instances. Each channel initializer injects only the factory plus its own transport-specific extras (SSL handler for TLS/WSS, WebSocket codecs for WS/WSS). This matches TBMQ's `MqttHandlerFactory` pattern and eliminates the 12-dependency duplication across initializers.

### WebSocket Pipeline
- **D-06:** WebSocket pipeline inserts HTTP and WebSocket handlers between the connection counter and the MQTT decoder. Pipeline order for WS: `idle` → `connectionCount` → `HttpServerCodec` → `HttpObjectAggregator` → `WebSocketServerProtocolHandler` → `WsBinaryFrameHandler` → `WsContinuationFrameHandler` → `WsTextFrameHandler` → `WsByteBufEncoder` → `MqttDecoder` → `MqttEncoder` → `MqttSessionHandler`. WSS additionally prepends `SslHandler` as first.
- **D-07:** Copy TBMQ's four WebSocket frame handlers (`WsBinaryFrameHandler`, `WsByteBufEncoder`, `WsContinuationFrameHandler`, `WsTextFrameHandler`) directly. They are small, self-contained, and battle-tested. `WsTextFrameHandler` disconnects clients that send text frames (MQTT is binary only).

### Port Configuration
- **D-08:** Default ports match TBMQ: WS on 8084 (`TBMQ_WS_PORT`), WSS on 8085 (`TBMQ_WSS_PORT`). WS enabled by default, WSS disabled by default (requires TLS certs). WebSocket path: `/mqtt` (matching TBMQ's `BrokerConstants.WS_PATH`). Max WebSocket content length: 65536 bytes.

### Claude's Discretion
- Exact abstract bootstrap class name and method decomposition
- Whether to use `@ConditionalOnProperty` or `isAutoStartup()` for WS/WSS enable/disable
- Handler factory method signatures and constructor design
- Integration test approach for WebSocket transport (Paho Java WS client vs. raw WS client)
- Whether WS/WSS listeners share the TCP bootstrap's thread pool or get their own (TBMQ uses separate pools per listener)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### TBMQ WebSocket Patterns (copy and adapt)
- `application/src/main/java/org/thingsboard/mqtt/broker/server/AbstractMqttWsChannelInitializer.java` — WS channel initializer base with HTTP/WS pipeline setup
- `application/src/main/java/org/thingsboard/mqtt/broker/server/ws/MqttWsServerBootstrap.java` — WS bootstrap pattern (SmartLifecycle, @ConditionalOnProperty)
- `application/src/main/java/org/thingsboard/mqtt/broker/server/ws/MqttWsChannelInitializer.java` — WS channel initializer (extends abstract WS base)
- `application/src/main/java/org/thingsboard/mqtt/broker/server/ws/MqttWsServerContext.java` — WS config bean (maxPayloadSize, subprotocols)
- `application/src/main/java/org/thingsboard/mqtt/broker/server/wss/MqttWssServerBootstrap.java` — WSS bootstrap pattern
- `application/src/main/java/org/thingsboard/mqtt/broker/server/wss/MqttWssChannelInitializer.java` — WSS channel initializer (WS base + SslHandler)
- `application/src/main/java/org/thingsboard/mqtt/broker/server/wss/MqttWssServerContext.java` — WSS config bean (certs, ciphers, subprotocols)
- `application/src/main/java/org/thingsboard/mqtt/broker/server/wss/MqttWssHandlerProvider.java` — WSS SSL handler factory

### TBMQ WebSocket Frame Handlers (copy directly)
- `application/src/main/java/org/thingsboard/mqtt/broker/server/wshandler/WsBinaryFrameHandler.java` — Binary frame → ByteBuf passthrough
- `application/src/main/java/org/thingsboard/mqtt/broker/server/wshandler/WsByteBufEncoder.java` — ByteBuf → BinaryWebSocketFrame encoder
- `application/src/main/java/org/thingsboard/mqtt/broker/server/wshandler/WsContinuationFrameHandler.java` — Continuation frame → ByteBuf passthrough
- `application/src/main/java/org/thingsboard/mqtt/broker/server/wshandler/WsTextFrameHandler.java` — Text frame rejection (MQTT is binary only)

### TBMQ Bootstrap Patterns (for abstract base extraction)
- `application/src/main/java/org/thingsboard/mqtt/broker/server/AbstractMqttServerBootstrap.java` — TBMQ's abstract bootstrap base
- `application/src/main/java/org/thingsboard/mqtt/broker/server/MqttHandlerFactory.java` — TBMQ's handler factory pattern

### TBMQ Constants
- `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/BrokerConstants.java` — `WS_PATH="/mqtt"`, `WS_MAX_CONTENT_LENGTH=65536`, subprotocol constants

### TBMQ YAML Configuration
- `application/src/main/resources/thingsboard-mqtt-broker.yml` — `listener.ws` and `listener.wss` sections (lines 155-201) for config structure reference

### Lightweight Current Code (to refactor)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttTcpServerBootstrap.java` — TCP bootstrap to refactor onto abstract base
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/tls/MqttSslServerBootstrap.java` — TLS bootstrap to refactor onto abstract base
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttChannelInitializer.java` — TCP initializer to refactor with handler factory
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/tls/MqttSslChannelInitializer.java` — TLS initializer to refactor with handler factory
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/tls/MqttSslHandlerProvider.java` — SSL handler provider (shared by MQTTS and WSS per D-01)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/NettyConfiguration.java` — Netty config to extend with WS/WSS ports
- `lightweight/src/main/resources/tbmq-lightweight.yml` — Config file to extend with WS/WSS sections

### Project Context
- `.planning/PROJECT.md` — Core value, constraints, key decisions
- `.planning/REQUIREMENTS.md` — TRAN-03, TRAN-04, TRAN-05
- `.planning/phases/01-foundation/01-CONTEXT.md` — Netty bootstrap, Docker, env var conventions
- `.planning/phases/04-security/04-CONTEXT.md` — TLS configuration, PEM loading, SslHandler patterns

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `MqttSslHandlerProvider` — SSL handler factory from Phase 4, reusable for WSS connections (D-01)
- `MqttTcpServerBootstrap` / `MqttSslServerBootstrap` — Established bootstrap pattern to extract into abstract base
- `MqttChannelInitializer` / `MqttSslChannelInitializer` — Pipeline patterns to refactor with handler factory
- `ConnectionCountHandler` — Shared @Sharable handler across all transports
- `BrokerMetricsService` — Micrometer metrics for connection tracking
- TBMQ's 4 WebSocket frame handlers — Small, self-contained, copy directly

### Established Patterns
- SmartLifecycle for ordered startup/shutdown (RocksDB < TCP < TLS < WS < WSS)
- Epoll/NIO transport detection with platform-aware EventLoopGroup creation
- Per-channel pipeline with named handlers for dynamic replacement (`"idle"`, `"ssl"`, etc.)
- Copy from TBMQ and trim (per user feedback)
- Interface + `Default` prefix naming convention
- `@RequiredArgsConstructor` for constructor injection

### Integration Points
- `MqttSessionHandler` — Same session handler for all transports (TCP/TLS/WS/WSS). WebSocket frame handlers decode WS frames to ByteBuf before MQTT decoder processes them.
- `MqttSslHandlerProvider.createSslHandler()` — Produces SslHandler for both MQTTS and WSS channel initializers
- `tbmq-lightweight.yml` — Add WS/WSS config sections (port, enabled, subprotocols, max content length)
- Existing integration tests — Extend with WS/WSS test cases using Paho Java's WebSocket transport

</code_context>

<specifics>
## Specific Ideas

- TBMQ's `AbstractMqttWsChannelInitializer.constructWsPipeline()` inserts HttpServerCodec + HttpObjectAggregator + WebSocketServerProtocolHandler + frame handlers in the correct order — copy this pipeline construction logic
- Netty's `WebSocketServerProtocolHandler` handles the HTTP upgrade handshake and `Sec-WebSocket-Protocol` header negotiation automatically when subprotocols are configured
- `WsBinaryFrameHandler` does `msg.content().retain()` before firing — critical for reference counting correctness
- `WsByteBufEncoder` wraps outbound ByteBuf in `BinaryWebSocketFrame` with retain — required because MqttEncoder produces ByteBuf, but WebSocket layer expects WebSocketFrame
- The `getLocalPort()` pattern from existing bootstraps should be preserved in the abstract base for test isolation (port=0 support)
- Paho Java client supports WebSocket transport via `MqttConnectOptions` with `ws://` or `wss://` URI scheme — usable for integration tests

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 05-websocket-transport*
*Context gathered: 2026-04-09*
