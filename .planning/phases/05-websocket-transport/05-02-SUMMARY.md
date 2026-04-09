---
phase: 05-websocket-transport
plan: 02
subsystem: websocket-transport
tags: [netty, websocket, wss, tls, mqtt-over-ws, integration-tests]
dependency_graph:
  requires: [05-01]
  provides: [WS transport TRAN-03, WSS transport TRAN-04, WS subprotocol header TRAN-05]
  affects: [MqttWsServerBootstrap, MqttWssServerBootstrap, AbstractMqttIntegrationTest]
tech_stack:
  added: []
  patterns:
    - "SmartLifecycle phasing: TCP=0, TLS=1, WS=2, WSS=3"
    - "WebSocketServerProtocolHandler subprotocol negotiation for Sec-WebSocket-Protocol header"
    - "WSS reuses MqttSslHandlerProvider from Phase 4 (no duplicate SSL context)"
key_files:
  created:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/ws/MqttWsServerBootstrap.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/ws/MqttWsChannelInitializer.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/wss/MqttWssServerBootstrap.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/wss/MqttWssChannelInitializer.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttWsIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttWssIntegrationTest.java
  modified:
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/AbstractMqttIntegrationTest.java
decisions:
  - "WSS isAutoStartup() checks both wssConfig.isEnabled() AND tlsConfig.isEnabled() — prevents WSS from starting without TLS certificates configured"
  - "MqttWssIntegrationTest uses separate @SpringBootTest context (not base) — WSS requires TLS enabled which other tests don't need"
  - "WS test URL must include /mqtt path — WebSocketServerProtocolHandler only upgrades at that path; missing path returns HTTP 400"
metrics:
  duration: "6 minutes"
  completed: "2026-04-09"
  tasks_completed: 2
  files_created: 6
  files_modified: 1
---

# Phase 05 Plan 02: WS and WSS Transport Bootstraps Summary

**One-liner:** MQTT WebSocket (ws://:8084) and Secure WebSocket (wss://:8085) transports with Netty pipeline, subprotocol negotiation, and end-to-end integration tests.

## What Was Built

### Task 1: WS and WSS Bootstraps and Channel Initializers

Four production source files created in the `lightweight` module:

**`MqttWsChannelInitializer`** — Netty channel initializer for WS transport. Pipeline order (D-06): `idle → connectionCount → HttpServerCodec → HttpObjectAggregator → WebSocketServerProtocolHandler("/mqtt", "mqttv3.1,mqtt") → WsBinaryFrameHandler → WsContinuationFrameHandler → WsTextFrameHandler → WsByteBufEncoder → MqttDecoder → MqttEncoder → MqttSessionHandler`. The `WebSocketServerProtocolHandler` automatically handles `Sec-WebSocket-Protocol` header negotiation (TRAN-05).

**`MqttWsServerBootstrap`** — SmartLifecycle phase 2, enabled by default, port 8084. Extends `AbstractServerBootstrap` following the established TCP/TLS pattern.

**`MqttWssChannelInitializer`** — Same as WS initializer but with `SslHandler` prepended as first handler (`"ssl" → idle → connectionCount → ...`). Reuses `MqttSslHandlerProvider` from Phase 4 (D-01 — no duplicate SSL context).

**`MqttWssServerBootstrap`** — SmartLifecycle phase 3. `isAutoStartup()` requires both `wssConfig.isEnabled() && tlsConfig.isEnabled()`. `start()` has explicit guard with warn log if WSS enabled but TLS not configured.

### Task 2: Integration Tests and Base Test Class Update

**`AbstractMqttIntegrationTest`** — Added `tbmq.ws.port=0` and `tbmq.wss.enabled=false` to base `@SpringBootTest` properties so all extending tests get a random WS port and WSS disabled by default.

**`MqttWsIntegrationTest`** — Three tests covering TRAN-03 and TRAN-05:
- `testWsConnect_thenConnackAccepted` — Paho WebSocket handshake validates `Sec-WebSocket-Protocol` header implicitly
- `testWsPublishSubscribe_thenMessageDelivered` — pub/sub over ws://
- `testWsAndTcpCoexist_thenBothWork` — WS publisher → TCP subscriber (same in-process dispatch)

**`MqttWssIntegrationTest`** — Separate `@SpringBootTest` context with TLS and WSS enabled. Three tests covering TRAN-04:
- `testWssConnect_thenConnackAccepted` — encrypted WS connect
- `testWssPublishSubscribe_thenMessageDelivered` — pub/sub over wss://
- `testWssAndWsCoexist_thenBothWork` — WSS publisher → WS subscriber (cross-transport dispatch)

## Requirements Completed

- **TRAN-03**: MQTT over WebSocket (ws://) — connect, publish, subscribe verified
- **TRAN-04**: MQTT over Secure WebSocket (wss://) — connect, publish, subscribe verified
- **TRAN-05**: `Sec-WebSocket-Protocol: mqtt` echoed in handshake — verified implicitly by Paho (throws `HandshakeFailedException` if missing)

## Decisions Made

1. **WSS isAutoStartup checks both flags** — prevents accidental WSS startup when TLS not configured; warn log guides operator when misconfigured
2. **MqttWssIntegrationTest uses its own @SpringBootTest** — WSS requires `tbmq.tls.enabled=true` and separate RocksDB path; sharing the base context would contaminate it
3. **WS URL must include /mqtt path** — `WebSocketServerProtocolHandler` only processes upgrade requests at the configured path; omitting `/mqtt` results in HTTP 400 (documented as Pitfall 6 in RESEARCH.md)

## Deviations from Plan

None — plan executed exactly as written.

## Test Results

| Test Class | Tests | Pass | Fail |
|---|---|---|---|
| MqttWsIntegrationTest | 3 | 3 | 0 |
| MqttWssIntegrationTest | 3 | 3 | 0 |

All 6 new tests pass. All pre-existing integration tests continue to pass when run individually. The full-suite `reuseForks=true` context threshold failures are pre-existing (55 errors without this plan's changes, 15 with — improved due to consistent base context) and are a known Surefire/Spring context issue documented in Phase 04 decisions.

## Self-Check: PASSED

All 6 created/modified files verified present. Both task commits (03d0746bc, 1392ba924) verified in git history.
