# Phase 5: WebSocket Transport - Research

**Researched:** 2026-04-09
**Domain:** Netty WebSocket pipeline, MQTT-over-WebSocket, server bootstrap refactoring
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** WSS reuses the same PEM certificate/key/truststore as the MQTTS listener (`TBMQ_TLS_*` env vars). No separate certificate configuration for WSS. The shared `MqttSslHandlerProvider` from Phase 4 creates `SslHandler` instances for both MQTTS and WSS channel initializers.
- **D-02:** Extract an `AbstractServerBootstrap` base class encapsulating shared Netty lifecycle: Epoll/NIO transport detection, boss/worker `EventLoopGroup` creation, `ServerBootstrap` configuration, bind, and graceful shutdown. Each concrete bootstrap (TCP, TLS, WS, WSS) provides only its configuration: port, `SmartLifecycle` phase, enabled flag, and channel initializer. This retroactively refactors the existing TCP and TLS bootstraps from Phases 1 and 4.
- **D-03:** SmartLifecycle phase ordering: TCP=0, TLS=1, WS=2, WSS=3. WS and WSS start after TCP and TLS; stop before them (descending phase order).
- **D-04:** Advertise both `mqttv3.1,mqtt` as supported WebSocket subprotocols from day one, matching TBMQ's default. The `mqtt` subprotocol represents both MQTT 3.1.1 and 5.0 per the OASIS standard.
- **D-05:** Create a `MqttSessionHandlerFactory` that encapsulates all session handler dependencies and produces new `MqttSessionHandler` instances. Each channel initializer injects only the factory plus its own transport-specific extras (SSL handler for TLS/WSS, WebSocket codecs for WS/WSS). This matches TBMQ's `MqttHandlerFactory` pattern and eliminates the 12-dependency duplication across initializers.
- **D-06:** WebSocket pipeline order: `idle` → `connectionCount` → `HttpServerCodec` → `HttpObjectAggregator` → `WebSocketServerProtocolHandler` → `WsBinaryFrameHandler` → `WsContinuationFrameHandler` → `WsTextFrameHandler` → `WsByteBufEncoder` → `MqttDecoder` → `MqttEncoder` → `MqttSessionHandler`. WSS additionally prepends `SslHandler` as first.
- **D-07:** Copy TBMQ's four WebSocket frame handlers (`WsBinaryFrameHandler`, `WsByteBufEncoder`, `WsContinuationFrameHandler`, `WsTextFrameHandler`) directly. They are small, self-contained, and battle-tested.
- **D-08:** Default ports: WS on 8084 (`TBMQ_WS_PORT`), WSS on 8085 (`TBMQ_WSS_PORT`). WS enabled by default, WSS disabled by default (requires TLS certs). WebSocket path: `/mqtt`. Max WebSocket content length: 65536 bytes.

### Claude's Discretion
- Exact abstract bootstrap class name and method decomposition
- Whether to use `@ConditionalOnProperty` or `isAutoStartup()` for WS/WSS enable/disable
- Handler factory method signatures and constructor design
- Integration test approach for WebSocket transport (Paho Java WS client vs. raw WS client)
- Whether WS/WSS listeners share the TCP bootstrap's thread pool or get their own (TBMQ uses separate pools per listener)

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| TRAN-03 | Broker accepts MQTT over WebSocket connections on configurable port (ws://) | AbstractServerBootstrap refactor + WS channel initializer with WebSocket pipeline |
| TRAN-04 | Broker accepts MQTT over secure WebSocket connections (wss://) with correct subprotocol header handling | WSS bootstrap + WSS channel initializer reusing MqttSslHandlerProvider from Phase 4 |
| TRAN-05 | Broker echoes `Sec-WebSocket-Protocol: mqtt` header in WebSocket handshake response for browser client compatibility | Netty's WebSocketServerProtocolHandler handles subprotocol negotiation automatically when configured with `mqttv3.1,mqtt` |
</phase_requirements>

## Summary

Phase 5 adds WebSocket (WS, port 8084) and secure WebSocket (WSS, port 8885) transports to the lightweight broker. The work has two parts: (1) a refactoring pass that extracts a shared `AbstractServerBootstrap` to eliminate bootstrap duplication across TCP, TLS, WS, and WSS, and (2) new WS/WSS bootstraps, channel initializers, and frame handlers. The approach is copy-and-adapt from TBMQ canonical files, which are now read in full.

TBMQ's `AbstractMqttWsChannelInitializer.constructWsPipeline()` inserts the complete WebSocket pipeline — `HttpServerCodec`, `HttpObjectAggregator`, `WebSocketServerProtocolHandler` (with subprotocol string), plus the four frame handlers — before the MQTT decoder. Netty's `WebSocketServerProtocolHandler` handles the HTTP-to-WebSocket upgrade handshake and emits the `Sec-WebSocket-Protocol` response header automatically when the subprotocol list is configured. The lightweight module already has all required Netty codecs (`netty-all` dependency) and the existing `MqttSslHandlerProvider` is ready for WSS reuse.

The key structural change is introducing a `MqttSessionHandlerFactory` (matching TBMQ's `MqttHandlerFactory` interface pattern) to centralize the 12-dependency `MqttSessionHandler` construction, removing duplication that would otherwise exist across `MqttChannelInitializer`, `MqttSslChannelInitializer`, `MqttWsChannelInitializer`, and `MqttWssChannelInitializer`.

**Primary recommendation:** Copy TBMQ's four WS frame handlers verbatim. Copy and trim the abstract WS channel initializer and bootstrap patterns. Extract `AbstractServerBootstrap` from the two existing bootstraps using the same lifecycle pattern already present (`SmartLifecycle`), adding Epoll/NIO transport detection and the `getLocalPort()` accessor.

## Standard Stack

### Core (already in pom.xml — no new dependencies needed)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `io.netty:netty-all` | 4.1.122.Final (pinned) | `HttpServerCodec`, `HttpObjectAggregator`, `WebSocketServerProtocolHandler`, frame handlers | Already declared; `netty-all` includes all Netty codecs including HTTP/WS |
| Eclipse Paho MQTT v3 | 1.2.5 (already in test scope) | WS/WSS integration test client via `ws://` and `wss://` URI schemes | Verified: JAR contains `WebSocketNetworkModule` and `WebSocketSecureNetworkModule` |

### Supporting (no new additions)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.bouncycastle:bcpkix-jdk18on` | 1.79 (already in pom.xml) | WSS test cert loading — same pattern as MQTTS tests | WSS integration tests |

**Installation:** No new dependencies. `netty-all` is already declared and covers all HTTP/WebSocket codecs.

**Version verification:** Netty 4.1.122.Final — `WebSocketServerProtocolHandler`, `HttpServerCodec`, `HttpObjectAggregator`, all frame handler types confirmed present in `netty-all`.

## Architecture Patterns

### Recommended Package Structure

```
lightweight/src/main/java/.../lightweight/server/
├── AbstractServerBootstrap.java         # NEW: extracted shared Netty lifecycle (D-02)
├── MqttSessionHandlerFactory.java       # NEW: factory producing MqttSessionHandler (D-05)
├── MqttChannelInitializer.java          # REFACTOR: inject factory, delegate to abstract base
├── MqttTcpServerBootstrap.java          # REFACTOR: extend AbstractServerBootstrap
├── MqttSessionHandler.java              # UNCHANGED
├── ConnectionCountHandler.java          # UNCHANGED
├── tls/
│   ├── MqttSslServerBootstrap.java      # REFACTOR: extend AbstractServerBootstrap
│   ├── MqttSslChannelInitializer.java   # REFACTOR: inject factory
│   └── MqttSslHandlerProvider.java      # UNCHANGED — reused by WSS (D-01)
├── ws/
│   ├── MqttWsServerBootstrap.java       # NEW: SmartLifecycle phase=2
│   ├── MqttWsChannelInitializer.java    # NEW: extends AbstractWsChannelInitializer
│   └── MqttWsConfiguration.java        # NEW: @ConfigurationProperties for WS settings
├── wss/
│   ├── MqttWssServerBootstrap.java      # NEW: SmartLifecycle phase=3, isAutoStartup()=config
│   ├── MqttWssChannelInitializer.java   # NEW: extends AbstractWsChannelInitializer + SslHandler
│   └── MqttWssConfiguration.java       # NEW: @ConfigurationProperties for WSS settings
└── wshandler/
    ├── WsBinaryFrameHandler.java        # NEW: copy directly from TBMQ
    ├── WsByteBufEncoder.java            # NEW: copy directly from TBMQ
    ├── WsContinuationFrameHandler.java  # NEW: copy directly from TBMQ
    └── WsTextFrameHandler.java          # NEW: copy directly from TBMQ (trim TBMQ imports)
```

### Pattern 1: AbstractServerBootstrap (D-02)

The two existing bootstraps (`MqttTcpServerBootstrap`, `MqttSslServerBootstrap`) share identical logic: Epoll/NIO detection, thread pool creation, `ServerBootstrap.bind()`, graceful shutdown, `getLocalPort()`. Extract this into an abstract base class implementing `SmartLifecycle`.

Abstract methods the concrete bootstrap must provide:
- `getPort()` — port to bind
- `getPhase()` — SmartLifecycle phase (0/1/2/3)
- `isAutoStartup()` — whether to start automatically (false for TLS/WSS when disabled)
- `getChannelInitializer()` — the `ChannelInitializer<SocketChannel>` to install

The abstract base holds `serverChannel`, `bossGroup`, `workerGroup`, `running` fields and provides `start()`, `stop()`, `isRunning()`, `getLocalPort()` implementations.

**Example (abstract base shape):**
```java
// Source: lightweight canonical, modeled after TBMQ AbstractMqttServerBootstrap
public abstract class AbstractServerBootstrap implements SmartLifecycle {

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;

    protected abstract int getPort();
    protected abstract ChannelInitializer<SocketChannel> getChannelInitializer();
    protected abstract String getServerName();

    @Override
    public void start() {
        boolean epoll = Epoll.isAvailable();
        bossGroup = epoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        workerGroup = epoll ? new EpollEventLoopGroup(workerThreadCount()) : new NioEventLoopGroup(workerThreadCount());
        Class<? extends ServerChannel> channelClass = epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
        try {
            serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(channelClass)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .childHandler(getChannelInitializer())
                    .bind(getPort()).sync().channel();
            running = true;
            log.info("{} started on port {} ({})", getServerName(), getLocalPort(), epoll ? "epoll" : "nio");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted binding " + getServerName(), e);
        }
    }

    public int getLocalPort() {
        return serverChannel == null ? -1 : ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }
}
```

### Pattern 2: MqttSessionHandlerFactory (D-05)

Both existing channel initializers construct `MqttSessionHandler` with 12 constructor parameters. Creating a factory centralizes this and allows each initializer to receive only what is specific to its transport.

```java
// Source: modeled after TBMQ MqttHandlerFactory pattern
@Component
@RequiredArgsConstructor
public class MqttSessionHandlerFactory {

    private final TbActorSystem actorSystem;
    private final ClientSessionRegistry sessionRegistry;
    private final MqttMessageGenerator messageGenerator;
    private final MqttConfiguration mqttConfig;
    private final SubscriptionRegistry subscriptionRegistry;
    private final RetainedMsgService retainedMsgService;
    private final LastWillService lastWillService;
    private final MsgDispatcherService msgDispatcherService;
    private final LightweightAuthService authService;
    private final AuthorizationRuleService authorizationRuleService;
    private final MeterRegistry meterRegistry;

    public MqttSessionHandler create() {
        return new MqttSessionHandler(actorSystem, sessionRegistry, messageGenerator, mqttConfig,
                subscriptionRegistry, retainedMsgService, lastWillService, msgDispatcherService,
                authService, authorizationRuleService, meterRegistry);
    }
}
```

After introducing the factory, each channel initializer injects `MqttSessionHandlerFactory` and `ConnectionCountHandler` plus its own transport-specific extras (e.g., `MqttSslHandlerProvider` for TLS, no extras for TCP, WS config for WS/WSS).

### Pattern 3: WebSocket Channel Pipeline (D-06)

The WebSocket pipeline must insert HTTP/WS handling between `connectionCount` and `MqttDecoder`. TBMQ's `AbstractMqttWsChannelInitializer.constructWsPipeline()` shows the exact order (read verbatim from source):

```java
// Source: TBMQ AbstractMqttWsChannelInitializer.constructWsPipeline()
pipeline.addLast(new HttpServerCodec());
pipeline.addLast(new HttpObjectAggregator(65536));  // WS_MAX_CONTENT_LENGTH
pipeline.addLast(new WebSocketServerProtocolHandler("/mqtt", "mqttv3.1,mqtt"));  // WS_PATH, subprotocols
pipeline.addLast(new WsBinaryFrameHandler());
pipeline.addLast(new WsContinuationFrameHandler());
pipeline.addLast(new WsTextFrameHandler());
pipeline.addLast(new WsByteBufEncoder());
// Then: MqttDecoder, MqttEncoder, MqttSessionHandler
```

For WSS: `SslHandler` is inserted **before** `idle` handler, before the HTTP/WS stack.

Complete WS pipeline order (named for `pipeline.replace()` compatibility):
```
"idle"              → IdleStateHandler(0,0,0)
"connectionCount"   → ConnectionCountHandler (@Sharable)
                      HttpServerCodec
                      HttpObjectAggregator(65536)
                      WebSocketServerProtocolHandler("/mqtt", "mqttv3.1,mqtt")
                      WsBinaryFrameHandler
                      WsContinuationFrameHandler
                      WsTextFrameHandler
                      WsByteBufEncoder
"decoder"           → MqttDecoder (new per channel)
"encoder"           → MqttEncoder.INSTANCE (@Sharable)
"handler"           → MqttSessionHandler (new per channel)
```

WSS prepends `"ssl" → SslHandler` before `"idle"`.

### Pattern 4: WS/WSS Bootstrap with SmartLifecycle (D-02, D-03)

TBMQ uses `@ConditionalOnProperty` + `@EventListener(ApplicationReadyEvent)` with `@Order`. Lightweight uses `SmartLifecycle` with `getPhase()` and `isAutoStartup()`. Preserve the SmartLifecycle approach for consistency with existing TCP (phase=0) and TLS (phase=1) bootstraps.

- **WS bootstrap:** `getPhase()=2`, `isAutoStartup()` returns the configured enabled flag (default `true`)
- **WSS bootstrap:** `getPhase()=3`, `isAutoStartup()` returns `wsConfig.isEnabled()` (default `false`)

### Pattern 5: Configuration (D-08)

Add two new `@ConfigurationProperties` beans, one per transport, reading from `tbmq.ws.*` and `tbmq.wss.*` namespaces in `tbmq-lightweight.yml`. Pattern follows `TlsConfiguration` (using `@Value`) or could use `@ConfigurationProperties` with `@Data` — either works. Keep consistent with Phase 4's `TlsConfiguration` approach using `@Value` fields:

```yaml
# tbmq-lightweight.yml additions
tbmq:
  ws:
    enabled: "${TBMQ_WS_ENABLED:true}"
    port: "${TBMQ_WS_PORT:8084}"
    sub-protocols: "${TBMQ_WS_SUB_PROTOCOLS:mqttv3.1,mqtt}"
    max-content-length: "${TBMQ_WS_MAX_CONTENT_LENGTH:65536}"
  wss:
    enabled: "${TBMQ_WSS_ENABLED:false}"
    port: "${TBMQ_WSS_PORT:8085}"
    sub-protocols: "${TBMQ_WSS_SUB_PROTOCOLS:mqttv3.1,mqtt}"
    max-content-length: "${TBMQ_WSS_MAX_CONTENT_LENGTH:65536}"
```

WSS reuses `tbmq.tls.*` for certificate configuration (D-01) — no new cert env vars.

### Anti-Patterns to Avoid

- **New SSL handler provider for WSS:** Do NOT create `MqttWssHandlerProvider`. The existing `MqttSslHandlerProvider` creates `SslHandler` instances and is already initialized when `tbmq.tls.enabled=true`. WSS simply calls `sslHandlerProvider.createSslHandler(ch)` directly (D-01).
- **Separate TLS config for WSS:** TBMQ has separate PEM paths for WSS (`LISTENER_WSS_PEM_CERT` etc.). Per D-01, lightweight shares `tbmq.tls.*`. Do not add separate cert paths.
- **Reference counting bugs:** `WsBinaryFrameHandler` and `WsContinuationFrameHandler` call `msg.content().retain()` before `fireChannelRead()`. This is not optional — without it, `SimpleChannelInboundHandler` releases the buffer before the downstream handler reads it.
- **WsByteBufEncoder retain missing:** `WsByteBufEncoder.encode()` calls `ReferenceCountUtil.retain(binaryWebSocketFrame)` because `MessageToMessageEncoder` takes ownership of added objects in `out`. Omitting this causes `IllegalReferenceCountException`.
- **Idle handler before SSL handler:** For WSS, `SslHandler` must be the absolute first handler. Do NOT insert `idle` before `ssl`.
- **WebSocketServerProtocolHandler with null subprotocols:** Passing `null` for subprotocols causes Netty to skip the `Sec-WebSocket-Protocol` response header. Must pass `"mqttv3.1,mqtt"` string.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| HTTP→WS upgrade handshake + `Sec-WebSocket-Protocol` header | Custom HTTP upgrade handler | `WebSocketServerProtocolHandler` from `netty-codec-http` | Handles RFC 6455 handshake, subprotocol negotiation, frame masking — dozens of edge cases |
| HTTP request/response framing | Custom HTTP framing | `HttpServerCodec` + `HttpObjectAggregator` | Manages chunked HTTP, keep-alive, pipelining |
| Binary WebSocket frame unpacking | Custom frame parser | `WsBinaryFrameHandler` (copy from TBMQ) | 5-line handler that already handles retain correctly |
| ByteBuf→WebSocket frame encoding | Custom encoder | `WsByteBufEncoder` (copy from TBMQ) | `MessageToMessageEncoder` lifecycle is subtle — retain is required |

**Key insight:** Netty's `WebSocketServerProtocolHandler` handles the entire RFC 6455 WebSocket handshake including subprotocol negotiation. When the client requests `Sec-WebSocket-Protocol: mqtt` and the server is configured with `"mqttv3.1,mqtt"`, Netty automatically echoes `Sec-WebSocket-Protocol: mqtt` in the response. Zero custom HTTP code required.

## Common Pitfalls

### Pitfall 1: WsByteBufEncoder Double-Free
**What goes wrong:** `IllegalReferenceCountException` or silent buffer corruption at high load.
**Why it happens:** `MessageToMessageEncoder.encode()` is given a `ByteBuf` (from `MqttEncoder`). When wrapped in a `BinaryWebSocketFrame` and added to `out`, Netty's encoder decrements the frame's refcount after encoding. Without the `ReferenceCountUtil.retain()` call, the frame is freed while the WebSocket layer is still writing it.
**How to avoid:** Copy `WsByteBufEncoder` verbatim from TBMQ — do not rewrite the retain logic.
**Warning signs:** `LEAK: BinaryWebSocketFrame.release() was not called before it's garbage-collected` in logs with `PARANOID` leak detector enabled (already set in Surefire argLine).

### Pitfall 2: MqttSslHandlerProvider Not Initialized for WSS
**What goes wrong:** `NullPointerException` or `IllegalStateException("SslContext not initialized")` when WSS listener starts.
**Why it happens:** `MqttSslHandlerProvider.init()` is guarded by `if (!tlsConfig.isEnabled()) return`. If WSS is enabled but `tbmq.tls.enabled=false`, the context is null.
**How to avoid:** WSS must require `tbmq.tls.enabled=true`. The WSS bootstrap's `isAutoStartup()` should check both `wsConfig.isWssEnabled()` AND `tlsConfig.isEnabled()`. Log a warning if WSS is configured enabled but TLS certs are not configured.
**Warning signs:** WSS listener starts, first connection causes NPE in `createSslHandler()`.

### Pitfall 3: WebSocketServerProtocolHandler Placement
**What goes wrong:** MQTT bytes arrive at `MqttDecoder` still wrapped in WebSocket frames — `DecoderException: io.netty.handler.codec.mqtt.MqttDecoder` with garbage input.
**Why it happens:** If `WebSocketServerProtocolHandler` is inserted after `MqttDecoder` in the pipeline, or if the frame-to-ByteBuf handlers are missing, the MQTT decoder receives raw WebSocket frame data.
**How to avoid:** Strictly follow pipeline order from D-06. WS handlers come BEFORE `MqttDecoder`.
**Warning signs:** MQTT decoder reporting `MALFORMED_PACKET` immediately after WebSocket handshake completes.

### Pitfall 4: Spring Context and WS Bootstrap Conditional
**What goes wrong:** WS bootstrap registers as a bean even when WS is disabled, but its channel initializer fails to bind; or the opposite — WSS beans are eagerly initialized when `tbmq.tls.enabled=false` and certs are absent.
**Why it happens:** Spring eagerly instantiates all `@Component` beans. If `MqttWssChannelInitializer` injects `MqttSslHandlerProvider` unconditionally and that provider's `sslContext` is null, the first WSS connection crashes.
**How to avoid:** For WSS, `isAutoStartup()` returning `false` prevents `start()` from running, but the bean itself is still constructed. Ensure `createSslHandler()` is only called during `initChannel()` (at connection time), not at bean construction time. The current `MqttSslHandlerProvider` design is safe — `sslContext` is only accessed at `createSslHandler()` call time.
**Warning signs:** `@SpringBootTest` with default config (TLS disabled) fails because `MqttSslHandlerProvider` field is injected into `MqttWssChannelInitializer` and throws during context loading.

**Fix:** Inject `MqttSslHandlerProvider` with `@Autowired(required = false)` in `MqttWssChannelInitializer`, or gate WSS bean creation. Alternatively, use `@ConditionalOnProperty` on `MqttWssServerBootstrap` (prevents the bootstrap from being created at all when disabled), while keeping the channel initializer unconditioned but checking for null provider at bind time.

### Pitfall 5: Integration Test Spring Context Isolation
**What goes wrong:** Adding WSS-related beans to the default `AbstractMqttIntegrationTest` context (which uses default config) causes bean initialization failures because TLS is disabled.
**Why it happens:** WSS channel initializer needs `MqttSslHandlerProvider` which needs `tbmq.tls.enabled=true` to initialize `sslContext`.
**How to avoid:** WS integration tests extend `AbstractMqttIntegrationTest` with WS port added as `@SpringBootTest` property. WSS integration tests create a separate `@SpringBootTest` context with TLS enabled (same pattern as `MqttTlsIntegrationTest`) and add WS port=0 property. Do NOT add WSS to the base test context.
**Warning signs:** `MqttWsIntegrationTest` fails with `IllegalStateException` about SSL not initialized.

### Pitfall 6: Paho WS Client URI Scheme
**What goes wrong:** Test fails with `MqttException: Connection refused` immediately.
**Why it happens:** Paho v3 requires `ws://host:port/path` including the path. Using `ws://127.0.0.1:8084` (no path) fails because the default path is `/mqtt` and Paho sends the upgrade to `/`.
**How to avoid:** Use `ws://127.0.0.1:` + port + `/mqtt` as the broker URI in tests. TBMQ's `BrokerConstants.WS_PATH = "/mqtt"` is the path.
**Warning signs:** `MqttException` at `connect()` with no server-side log entry — the HTTP upgrade request path mismatch causes an immediate HTTP 400.

## Code Examples

Verified patterns from TBMQ source (read in full):

### WsBinaryFrameHandler (copy verbatim)
```java
// Source: TBMQ application/src/main/java/org/thingsboard/mqtt/broker/server/wshandler/WsBinaryFrameHandler.java
public class WsBinaryFrameHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame msg) throws Exception {
        ctx.fireChannelRead(msg.content().retain());  // retain() is CRITICAL
    }
}
```

### WsByteBufEncoder (copy verbatim)
```java
// Source: TBMQ application/src/main/java/org/thingsboard/mqtt/broker/server/wshandler/WsByteBufEncoder.java
public class WsByteBufEncoder extends MessageToMessageEncoder<ByteBuf> {
    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        final BinaryWebSocketFrame binaryWebSocketFrame = new BinaryWebSocketFrame(msg);
        ReferenceCountUtil.retain(binaryWebSocketFrame);  // retain() is CRITICAL
        out.add(binaryWebSocketFrame);
    }
}
```

### WsContinuationFrameHandler (copy verbatim)
```java
// Source: TBMQ application/src/main/java/org/thingsboard/mqtt/broker/server/wshandler/WsContinuationFrameHandler.java
public class WsContinuationFrameHandler extends SimpleChannelInboundHandler<ContinuationWebSocketFrame> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ContinuationWebSocketFrame msg) throws Exception {
        ctx.fireChannelRead(msg.content().retain());  // retain() is CRITICAL
    }
}
```

### WsTextFrameHandler (copy verbatim, trim TBMQ ADDRESS attribute import)
```java
// Source: TBMQ application/src/main/java/org/thingsboard/mqtt/broker/server/wshandler/WsTextFrameHandler.java
// NOTE: Remove import of MqttSessionHandler.ADDRESS if that attribute key does not exist in lightweight;
// the ADDRESS attribute is on MqttSessionHandler — check if it exists before copying that reference.
@Slf4j
public class WsTextFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final TextWebSocketFrame msg) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Received illegal Text WebSocket frame. Disconnecting client.");
        }
        ctx.channel().disconnect();
    }
}
```

### WS Channel Initializer initChannel() structure
```java
// Source: modeled after TBMQ AbstractMqttWsChannelInitializer + AbstractMqttChannelInitializer
@Override
protected void initChannel(SocketChannel ch) {
    ch.pipeline()
        .addLast("idle", new IdleStateHandler(0, 0, 0))
        .addLast("connectionCount", connectionCountHandler)
        // WebSocket pipeline (inserted before MQTT codec):
        .addLast(new HttpServerCodec())
        .addLast(new HttpObjectAggregator(wsConfig.getMaxContentLength()))
        .addLast(new WebSocketServerProtocolHandler(WS_PATH, wsConfig.getSubProtocols()))
        .addLast(new WsBinaryFrameHandler())
        .addLast(new WsContinuationFrameHandler())
        .addLast(new WsTextFrameHandler())
        .addLast(new WsByteBufEncoder())
        // MQTT codec (after WS frame decoding):
        .addLast("decoder", new MqttDecoder(mqttConfig.getMaxPayloadSize(), mqttConfig.getMaxClientIdLength()))
        .addLast("encoder", MqttEncoder.INSTANCE)
        .addLast("handler", sessionHandlerFactory.create());
}
```

### WSS Channel Initializer — SSL first
```java
// WSS: prepend SslHandler before idle handler
@Override
protected void initChannel(SocketChannel ch) {
    ch.pipeline()
        .addLast("ssl", sslHandlerProvider.createSslHandler(ch))  // FIRST
        .addLast("idle", new IdleStateHandler(0, 0, 0))
        // ... same as WS from here
}
```

### Paho Java WS Integration Test URL pattern
```java
// Source: Paho v3 1.2.5 — WebSocket support verified in JAR (WebSocketNetworkModuleFactory.class present)
// ws:// scheme triggers WebSocket transport; path /mqtt must match WebSocketServerProtocolHandler path
String wsUrl = "ws://127.0.0.1:" + wsServer.getLocalPort() + "/mqtt";
MqttClient client = new MqttClient(wsUrl, "ws-test-client", new MemoryPersistence());
client.connect(defaultConnectOptions());

// WSS equivalent:
String wssUrl = "wss://127.0.0.1:" + wssServer.getLocalPort() + "/mqtt";
MqttConnectOptions opts = new MqttConnectOptions();
opts.setSocketFactory(createTrustingSocketFactory());  // same helper as MqttTlsIntegrationTest
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Separate SSL context per transport | Shared `SslContext` in `MqttSslHandlerProvider` | Phase 4 (lightweight design) | WSS reuses MQTTS certs — no new config |
| Duplicate Netty bootstrap per transport | `AbstractServerBootstrap` base class | This phase (D-02) | 4 bootstraps share ~80% of lifecycle code |
| 12-arg `MqttSessionHandler` constructor in each initializer | `MqttSessionHandlerFactory.create()` | This phase (D-05) | Single injection point; initializers have 2-3 dependencies |

## Environment Availability

Step 2.6: The phase depends only on code and configuration — no external services, databases, or CLIs beyond what is already running. The Netty WebSocket codecs are bundled in `netty-all` which is already declared. No additional tooling required.

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| `netty-all` 4.1.122.Final | WS/WSS pipeline codecs | Yes (in pom.xml) | 4.1.122.Final | — |
| Eclipse Paho MQTT v3 1.2.5 | Integration tests | Yes (in test scope, JAR in ~/.m2) | 1.2.5 | — |
| Paho WS support (`WebSocketNetworkModule`) | WS/WSS integration tests | Yes (verified in JAR) | Bundled in 1.2.5 | — |
| Test certs (`tls/server.pem`, `ca.pem`, etc.) | WSS integration tests | Yes (src/test/resources/tls/) | Phase 4 | — |

**Missing dependencies with no fallback:** None.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test |
| Config file | `lightweight/pom.xml` (Surefire 3.2.5 with `reuseForks=true`) |
| Quick run command | `cd lightweight && mvn test -pl . -Dtest=MqttWsIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` |
| Full suite command | `cd lightweight && mvn test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| TRAN-03 | WS listener accepts MQTT connect, publish, subscribe over `ws://` | integration | `mvn test -Dtest=MqttWsIntegrationTest` | No — Wave 0 |
| TRAN-04 | WSS listener accepts MQTT connect over `wss://` with TLS; WS and WSS coexist | integration | `mvn test -Dtest=MqttWssIntegrationTest` | No — Wave 0 |
| TRAN-05 | `Sec-WebSocket-Protocol: mqtt` in handshake response; browser-compatible | smoke/integration (covered by WS connect succeeding with Paho WS client, which validates subprotocol) | `mvn test -Dtest=MqttWsIntegrationTest` | No — Wave 0 |

TRAN-05 is verified implicitly: Paho's `WebSocketHandshake` validates the server's `Sec-WebSocket-Protocol` response. If the header is absent or mismatched, Paho throws `HandshakeFailedException` and the connect test fails.

### Sampling Rate
- **Per task commit:** `mvn test -pl lightweight -Dtest=MqttWsIntegrationTest,MqttWssIntegrationTest`
- **Per wave merge:** `mvn test -pl lightweight`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `lightweight/src/test/java/.../mqtt/MqttWsIntegrationTest.java` — covers TRAN-03 and TRAN-05
- [ ] `lightweight/src/test/java/.../mqtt/MqttWssIntegrationTest.java` — covers TRAN-04

*(No new test infrastructure needed — existing `AbstractMqttIntegrationTest` base, Paho client, and test cert resources from Phase 4 are reused)*

## Sources

### Primary (HIGH confidence)
- TBMQ source — `application/src/main/java/org/thingsboard/mqtt/broker/server/AbstractMqttWsChannelInitializer.java` — exact WebSocket pipeline construction logic
- TBMQ source — `application/src/main/java/org/thingsboard/mqtt/broker/server/ws/MqttWsServerBootstrap.java` — WS bootstrap pattern
- TBMQ source — `application/src/main/java/org/thingsboard/mqtt/broker/server/ws/MqttWsChannelInitializer.java` — WS initializer pattern
- TBMQ source — `application/src/main/java/org/thingsboard/mqtt/broker/server/wss/MqttWssChannelInitializer.java` — WSS + SSL pattern
- TBMQ source — all four `wshandler/*.java` — exact frame handler code (read in full)
- TBMQ source — `AbstractMqttServerBootstrap.java` + `MqttHandlerFactory.java` — abstract base and factory patterns
- Lightweight source — `MqttTcpServerBootstrap.java`, `MqttSslServerBootstrap.java` — current state to be refactored
- Lightweight source — `MqttChannelInitializer.java`, `MqttSslChannelInitializer.java` — initializers to be refactored
- Lightweight source — `MqttSslHandlerProvider.java` — SSL handler factory reused for WSS
- Paho JAR inspection — `WebSocketNetworkModule.class`, `WebSocketSecureNetworkModule.class` confirmed in v1.2.5 — WS/WSS test transport available

### Secondary (MEDIUM confidence)
- `thingsboard-mqtt-broker.yml` lines 155-220 — TBMQ WS/WSS YAML configuration structure for env var naming reference

### Tertiary (LOW confidence)
- None

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in pom.xml, JAR contents verified
- Architecture: HIGH — patterns read from live TBMQ source; lightweight pattern confirmed consistent with TBMQ
- Pitfalls: HIGH — reference counting issues verified from TBMQ source annotations; Spring conditional behavior inferred from existing Phase 4 TLS pattern

**Research date:** 2026-04-09
**Valid until:** 2026-05-09 (stable Netty/Spring patterns; no external dependencies)
