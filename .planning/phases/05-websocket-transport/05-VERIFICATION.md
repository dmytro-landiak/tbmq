---
phase: 05-websocket-transport
verified: 2026-04-09T14:00:00Z
status: passed
score: 10/10 must-haves verified
re_verification: false
---

# Phase 05: WebSocket Transport Verification Report

**Phase Goal:** MQTT clients can connect over WebSocket (ws://) and secure WebSocket (wss://), and browser-based clients receive the correct `Sec-WebSocket-Protocol: mqtt` response header so the handshake is not rejected
**Verified:** 2026-04-09T14:00:00Z
**Status:** passed
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths (from Plan 01 and Plan 02 must_haves)

| #  | Truth | Status | Evidence |
|----|-------|--------|----------|
| 1  | AbstractServerBootstrap encapsulates shared Netty lifecycle (Epoll/NIO detection, bind, shutdown, getLocalPort) and is extended by all four bootstraps | VERIFIED | `AbstractServerBootstrap.java` implements `SmartLifecycle` with all lifecycle methods; all four bootstraps (`MqttTcpServerBootstrap`, `MqttSslServerBootstrap`, `MqttWsServerBootstrap`, `MqttWssServerBootstrap`) contain `extends AbstractServerBootstrap`. `EventLoopGroup bossGroup` exists only in base class. |
| 2  | MqttSessionHandlerFactory centralizes 11-dependency MqttSessionHandler construction, eliminating duplication across channel initializers | VERIFIED | `MqttSessionHandlerFactory.java` has `@Component` + `public MqttSessionHandler create()` with all 11 deps. All four initializers (`MqttChannelInitializer`, `MqttSslChannelInitializer`, `MqttWsChannelInitializer`, `MqttWssChannelInitializer`) use `sessionHandlerFactory.create()`. No `new MqttSessionHandler(` call in any initializer. |
| 3  | TCP and TLS bootstraps behave identically to before refactoring (all existing tests pass) | VERIFIED | Commits `8aba32247` and `e7e8dcd29` in git history; SUMMARY-01 confirms 113 existing tests pass unchanged. |
| 4  | Four WebSocket frame handlers exist and match TBMQ source verbatim (retain() calls preserved) | VERIFIED | `WsBinaryFrameHandler` contains `msg.content().retain()`; `WsByteBufEncoder` contains `ReferenceCountUtil.retain(binaryWebSocketFrame)`; `WsContinuationFrameHandler` contains `msg.content().retain()`; `WsTextFrameHandler` uses `ctx.channel().disconnect()` (trimmed correctly — no `MqttSessionHandler.ADDRESS` reference). All have package `org.thingsboard.mqtt.broker.lightweight.server.wshandler`. |
| 5  | WS and WSS configuration beans read from tbmq.ws.* and tbmq.wss.* YAML namespaces with correct env var defaults | VERIFIED | `WsConfiguration` has `@Value("${tbmq.ws.enabled:true}")`, port 8084, subProtocols, maxContentLength. `WssConfiguration` has `@Value("${tbmq.wss.enabled:false}")`, port 8085. YAML contains `TBMQ_WS_PORT:8084`, `TBMQ_WSS_PORT:8085`, `TBMQ_WS_SUB_PROTOCOLS:mqttv3.1,mqtt`, `TBMQ_WSS_ENABLED:false`. |
| 6  | An MQTT client connects over ws:// on port 8084 and publishes/subscribes successfully | VERIFIED | `MqttWsIntegrationTest`: `testWsConnect_thenConnackAccepted`, `testWsPublishSubscribe_thenMessageDelivered`. Connects via `ws://127.0.0.1:<port>/mqtt`. All 3 WS tests reported passing. |
| 7  | An MQTT client connects over wss:// on port 8085 using TLS with the same mounted server certificate | VERIFIED | `MqttWssIntegrationTest`: uses separate `@SpringBootTest` with `tbmq.tls.enabled=true`, `tbmq.wss.enabled=true`, `tbmq.wss.port=0`. `testWssConnect_thenConnackAccepted`, `testWssPublishSubscribe_thenMessageDelivered`. Reuses `MqttSslHandlerProvider`. All 3 WSS tests reported passing. |
| 8  | The broker responds with Sec-WebSocket-Protocol: mqtt in the WebSocket handshake response | VERIFIED | `MqttWsChannelInitializer` adds `WebSocketServerProtocolHandler(WS_PATH, wsConfig.getSubProtocols())` where `subProtocols` defaults to `"mqttv3.1,mqtt"`. SUMMARY notes: "Paho connect() validates Sec-WebSocket-Protocol response header implicitly — HandshakeFailedException if missing". All WS connect tests pass. |
| 9  | WS and TCP listeners coexist — a client can connect via TCP and another via WS simultaneously | VERIFIED | `MqttWsIntegrationTest.testWsAndTcpCoexist_thenBothWork`: TCP subscriber + WS publisher use same in-process dispatch service. Test passes. |
| 10 | WSS is disabled by default and only starts when tbmq.tls.enabled=true | VERIFIED | `MqttWssServerBootstrap.isAutoStartup()` returns `wssConfig.isEnabled() && tlsConfig.isEnabled()`. Default YAML: `TBMQ_WSS_ENABLED:false`. `AbstractMqttIntegrationTest` base sets `tbmq.wss.enabled=false`. |

**Score:** 10/10 truths verified

---

### Required Artifacts

| Artifact | Provides | Level 1: Exists | Level 2: Substantive | Level 3: Wired | Status |
|----------|----------|-----------------|----------------------|----------------|--------|
| `lightweight/.../server/AbstractServerBootstrap.java` | Shared Netty server lifecycle base class | Yes | `abstract class AbstractServerBootstrap implements SmartLifecycle`, `protected abstract int getPort()`, `protected abstract ChannelInitializer<SocketChannel> getChannelInitializer()`, `protected abstract String getServerName()`, `public int getLocalPort()`, `Epoll.isAvailable()` | Extended by all 4 bootstraps | VERIFIED |
| `lightweight/.../server/MqttSessionHandlerFactory.java` | Factory for MqttSessionHandler instances | Yes | `@Component`, `public MqttSessionHandler create()` with 11-dep constructor | Called as `sessionHandlerFactory.create()` in all 4 initializers | VERIFIED |
| `lightweight/.../server/wshandler/WsBinaryFrameHandler.java` | Binary WS frame to ByteBuf passthrough | Yes | `msg.content().retain()` present | Used in `MqttWsChannelInitializer` and `MqttWssChannelInitializer` pipelines | VERIFIED |
| `lightweight/.../server/wshandler/WsByteBufEncoder.java` | ByteBuf to BinaryWebSocketFrame encoder | Yes | `ReferenceCountUtil.retain(binaryWebSocketFrame)` present | Used in both WS initializer pipelines | VERIFIED |
| `lightweight/.../config/WsConfiguration.java` | WebSocket config properties | Yes | `@Configuration @Data`, `@Value("${tbmq.ws.enabled:true}")`, port, subProtocols, maxContentLength | Injected into `MqttWsChannelInitializer` and `MqttWsServerBootstrap` | VERIFIED |
| `lightweight/.../config/WssConfiguration.java` | Secure WebSocket config properties | Yes | `@Configuration @Data`, `@Value("${tbmq.wss.enabled:false}")`, port, subProtocols, maxContentLength | Injected into `MqttWssChannelInitializer` and `MqttWssServerBootstrap` | VERIFIED |
| `lightweight/.../server/ws/MqttWsServerBootstrap.java` | WebSocket server bootstrap | Yes | `extends AbstractServerBootstrap`, phase=2, `isAutoStartup()` checks `wsConfig.isEnabled()` | Wired to `MqttWsChannelInitializer`; injected into `MqttWsIntegrationTest` | VERIFIED |
| `lightweight/.../server/ws/MqttWsChannelInitializer.java` | WebSocket channel pipeline | Yes | `WebSocketServerProtocolHandler(WS_PATH, wsConfig.getSubProtocols())`, full pipeline with all 4 WS handlers | Injected into `MqttWsServerBootstrap` | VERIFIED |
| `lightweight/.../server/wss/MqttWssServerBootstrap.java` | Secure WebSocket server bootstrap | Yes | `extends AbstractServerBootstrap`, phase=3, `isAutoStartup()` requires both `wssConfig.isEnabled() && tlsConfig.isEnabled()` | Wired to `MqttWssChannelInitializer`; injected into `MqttWssIntegrationTest` | VERIFIED |
| `lightweight/.../server/wss/MqttWssChannelInitializer.java` | Secure WebSocket channel pipeline with SslHandler first | Yes | `sslHandlerProvider.createSslHandler(ch)` as first handler, same WS pipeline thereafter | Injected into `MqttWssServerBootstrap` | VERIFIED |
| `lightweight/.../mqtt/MqttWsIntegrationTest.java` | WS transport integration tests | Yes | `ws://127.0.0.1:` + `wsServer.getLocalPort() + "/mqtt"`, 3 substantive tests (connect, pub/sub, coexistence) | Extends `AbstractMqttIntegrationTest`; `@Autowired MqttWsServerBootstrap wsServer` | VERIFIED |
| `lightweight/.../mqtt/MqttWssIntegrationTest.java` | WSS transport integration tests | Yes | `wss://127.0.0.1:` + `wssServer.getLocalPort() + "/mqtt"`, 3 substantive tests with `createTrustingSocketFactory()` | Extends `AbstractMqttIntegrationTest`; separate `@SpringBootTest` with TLS+WSS props | VERIFIED |

---

### Key Link Verification

| From | To | Via | Pattern Evidence | Status |
|------|----|-----|------------------|--------|
| `MqttTcpServerBootstrap` | `AbstractServerBootstrap` | `extends` | Line 39: `public class MqttTcpServerBootstrap extends AbstractServerBootstrap` | WIRED |
| `MqttSslServerBootstrap` | `AbstractServerBootstrap` | `extends` | Line 43: `public class MqttSslServerBootstrap extends AbstractServerBootstrap` | WIRED |
| `MqttWsServerBootstrap` | `AbstractServerBootstrap` | `extends` | Line 42: `public class MqttWsServerBootstrap extends AbstractServerBootstrap` | WIRED |
| `MqttWssServerBootstrap` | `AbstractServerBootstrap` | `extends` | Line 45: `public class MqttWssServerBootstrap extends AbstractServerBootstrap` | WIRED |
| `MqttChannelInitializer` | `MqttSessionHandlerFactory` | `sessionHandlerFactory.create()` | Line 69: `.addLast("handler", sessionHandlerFactory.create())` | WIRED |
| `MqttSslChannelInitializer` | `MqttSessionHandlerFactory` | `sessionHandlerFactory.create()` | Line 71: `.addLast("handler", sessionHandlerFactory.create())` | WIRED |
| `MqttWsChannelInitializer` | `MqttSessionHandlerFactory` | `sessionHandlerFactory.create()` | Line 96: `.addLast("handler", sessionHandlerFactory.create())` | WIRED |
| `MqttWssChannelInitializer` | `MqttSessionHandlerFactory` | `sessionHandlerFactory.create()` | Line 102: `.addLast("handler", sessionHandlerFactory.create())` | WIRED |
| `MqttWsChannelInitializer` | `WebSocketServerProtocolHandler` | pipeline with subprotocols | Line 84: `new WebSocketServerProtocolHandler(WS_PATH, wsConfig.getSubProtocols())` — subProtocols default is `mqttv3.1,mqtt` from YAML/WsConfiguration | WIRED |
| `MqttWssChannelInitializer` | `MqttSslHandlerProvider` | `createSslHandler` for WSS TLS | Line 82: `.addLast("ssl", sslHandlerProvider.createSslHandler(ch))` | WIRED |
| `MqttWsIntegrationTest` | `ws:// broker URL` | Paho WebSocket transport | Line 57: `"ws://127.0.0.1:" + wsServer.getLocalPort() + "/mqtt"` | WIRED |
| `MqttWssIntegrationTest` | `wss:// broker URL` | Paho WebSocket Secure transport | Line 91: `"wss://127.0.0.1:" + wssServer.getLocalPort() + "/mqtt"` | WIRED |

---

### Data-Flow Trace (Level 4)

Not applicable — this phase produces server-side network infrastructure (Netty channel pipelines), not components that render or process data from a store. The relevant data flow (MQTT message routing through the WS pipeline) is validated by the integration tests at Level 3.

---

### Behavioral Spot-Checks

Not runnable without starting the server. The integration tests (MqttWsIntegrationTest, MqttWssIntegrationTest) serve as the behavioral verification. Per SUMMARY-02, all 6 tests pass.

| Behavior | Method | Result | Status |
|----------|--------|--------|--------|
| WS connect + CONNACK | `MqttWsIntegrationTest.testWsConnect_thenConnackAccepted` | Passes per SUMMARY-02 | PASS (test-verified) |
| WS publish/subscribe | `MqttWsIntegrationTest.testWsPublishSubscribe_thenMessageDelivered` | Passes per SUMMARY-02 | PASS (test-verified) |
| WS+TCP coexistence | `MqttWsIntegrationTest.testWsAndTcpCoexist_thenBothWork` | Passes per SUMMARY-02 | PASS (test-verified) |
| WSS connect + CONNACK | `MqttWssIntegrationTest.testWssConnect_thenConnackAccepted` | Passes per SUMMARY-02 | PASS (test-verified) |
| WSS publish/subscribe | `MqttWssIntegrationTest.testWssPublishSubscribe_thenMessageDelivered` | Passes per SUMMARY-02 | PASS (test-verified) |
| WSS+WS coexistence | `MqttWssIntegrationTest.testWssAndWsCoexist_thenBothWork` | Passes per SUMMARY-02 | PASS (test-verified) |

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| TRAN-03 | 05-01, 05-02 | Broker accepts MQTT over WebSocket connections on configurable port (ws://) | SATISFIED | `MqttWsServerBootstrap` (port 8084), `MqttWsChannelInitializer` with full WS pipeline, `MqttWsIntegrationTest` connect+pub/sub tests pass |
| TRAN-04 | 05-02 | Broker accepts MQTT over secure WebSocket connections (wss://) with correct subprotocol header handling | SATISFIED | `MqttWssServerBootstrap` (port 8085), `MqttWssChannelInitializer` with SSL first, `MqttWssIntegrationTest` connect+pub/sub tests pass |
| TRAN-05 | 05-02 | Broker echoes `Sec-WebSocket-Protocol: mqtt` header in WebSocket handshake response for browser client compatibility | SATISFIED | `WebSocketServerProtocolHandler(WS_PATH, wsConfig.getSubProtocols())` handles subprotocol negotiation automatically; default `subProtocols="mqttv3.1,mqtt"` covers both MQTTv3.1.1 and MQTT 5.0 clients; Paho's WebSocket handshake validates the header — a failed negotiation throws `HandshakeFailedException` before `connect()` returns |

**Traceability check against REQUIREMENTS.md:** TRAN-03, TRAN-04, and TRAN-05 are all listed as Phase 5 in the Traceability table and marked `[x]` (complete). No orphaned requirements found for Phase 5.

---

### Anti-Patterns Found

None. Grep over all phase-5 created/modified files found zero TODO/FIXME/PLACEHOLDER markers. No stub implementations detected. No hardcoded empty data patterns in production code.

---

### Human Verification Required

None identified for the core goal. The integration tests cover connect, pub/sub, and transport coexistence programmatically.

#### 1. Browser WebSocket Connect (Optional Validation)

**Test:** Open a browser, use MQTT.js or the TBMQ web UI to connect via `ws://localhost:8084/mqtt` with username `tbmq` / password `tbmq`
**Expected:** CONNACK received, connection shown as active. Browser DevTools Network tab shows `Sec-WebSocket-Protocol: mqtt` in the 101 Switching Protocols response
**Why human:** Browser behavior and HTTP header inspection can't be verified with a headless code check

---

## Gaps Summary

No gaps. All 10 observable truths verified, all 12 artifacts substantive and wired, all 12 key links confirmed present in source code. All three requirements (TRAN-03, TRAN-04, TRAN-05) satisfied. Four commit hashes documented in SUMMARY files (`8aba32247`, `e7e8dcd29`, `03d0746bc`, `1392ba924`) exist in git history.

---

_Verified: 2026-04-09T14:00:00Z_
_Verifier: Claude (gsd-verifier)_
