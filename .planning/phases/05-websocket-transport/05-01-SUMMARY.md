---
phase: 05-websocket-transport
plan: 01
subsystem: infra
tags: [netty, websocket, mqtt, server-bootstrap, channel-initializer, refactoring]

# Dependency graph
requires:
  - phase: 04-security
    provides: MqttSslServerBootstrap, MqttSslChannelInitializer, TlsConfiguration
  - phase: 02-core-protocol-mqtt-3-1-1
    provides: MqttSessionHandler, MqttChannelInitializer, MqttTcpServerBootstrap
provides:
  - AbstractServerBootstrap (shared Netty lifecycle base class for all 4 transports)
  - MqttSessionHandlerFactory (centralized 11-dep MqttSessionHandler construction)
  - Refactored MqttTcpServerBootstrap extending AbstractServerBootstrap
  - Refactored MqttSslServerBootstrap extending AbstractServerBootstrap
  - Simplified MqttChannelInitializer using factory (3 fields vs 12)
  - Simplified MqttSslChannelInitializer using factory (4 fields vs 13)
  - Four TBMQ WS frame handlers (WsBinaryFrameHandler, WsByteBufEncoder, WsContinuationFrameHandler, WsTextFrameHandler)
  - WsConfiguration and WssConfiguration config beans
  - YAML config for tbmq.ws.* and tbmq.wss.* namespaces
affects: [05-02-ws-wss-bootstraps, phase-06, phase-07]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - AbstractServerBootstrap SmartLifecycle base class pattern (extend to add new transport)
    - MqttSessionHandlerFactory factory pattern (eliminates N-dep duplication across initializers)
    - WS frame handler copy pattern (copy verbatim from TBMQ, update package only)

key-files:
  created:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/AbstractServerBootstrap.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandlerFactory.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/wshandler/WsBinaryFrameHandler.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/wshandler/WsByteBufEncoder.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/wshandler/WsContinuationFrameHandler.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/wshandler/WsTextFrameHandler.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/WsConfiguration.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/WssConfiguration.java
  modified:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttTcpServerBootstrap.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttChannelInitializer.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/tls/MqttSslServerBootstrap.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/tls/MqttSslChannelInitializer.java
    - lightweight/src/main/resources/tbmq-lightweight.yml

key-decisions:
  - "AbstractServerBootstrap does NOT override getPhase/isAutoStartup — each subclass controls its own SmartLifecycle phase and startup conditions"
  - "WsTextFrameHandler trimmed: MqttSessionHandler.ADDRESS attribute reference removed since lightweight MqttSessionHandler has no ADDRESS field"
  - "WsConfiguration uses @Value (not @ConfigurationProperties) to match TlsConfiguration pattern in lightweight"

patterns-established:
  - "New transport bootstrap = extend AbstractServerBootstrap + override getPort/getChannelInitializer/getServerName"
  - "New channel initializer = inject MqttSessionHandlerFactory + call sessionHandlerFactory.create() in initChannel()"

requirements-completed: [TRAN-03, TRAN-04]

# Metrics
duration: 5min
completed: 2026-04-09
---

# Phase 05 Plan 01: WebSocket Transport Infrastructure Summary

**AbstractServerBootstrap base class and MqttSessionHandlerFactory eliminate bootstrap/initializer duplication, with 4 WS frame handlers and WS/WSS config beans ready for the WS/WSS bootstraps in plan 05-02**

## Performance

- **Duration:** 5 min
- **Started:** 2026-04-09T12:09:59Z
- **Completed:** 2026-04-09T12:14:36Z
- **Tasks:** 2
- **Files modified:** 13

## Accomplishments
- AbstractServerBootstrap implements shared Netty lifecycle (Epoll/NIO detection, boss/worker group creation, bind, graceful shutdown) — eliminates ~80 lines of duplication between TCP and TLS bootstraps
- MqttSessionHandlerFactory centralizes MqttSessionHandler construction with 11 dependencies — channel initializers now have 3 and 4 fields respectively instead of 12 and 13
- 4 TBMQ WS frame handlers copied verbatim to lightweight wshandler package with critical retain() calls preserved
- WsConfiguration and WssConfiguration beans created with env var defaults; YAML updated with tbmq.ws.* and tbmq.wss.* namespaces
- All 113 existing tests pass unchanged (refactoring is behavior-preserving)

## Task Commits

Each task was committed atomically:

1. **Task 1: Extract AbstractServerBootstrap + MqttSessionHandlerFactory, refactor TCP/TLS** - `8aba32247` (feat)
2. **Task 2: Copy TBMQ WS frame handlers + create WS/WSS config beans + update YAML** - `e7e8dcd29` (feat)

## Files Created/Modified
- `lightweight/src/main/java/.../server/AbstractServerBootstrap.java` - Shared SmartLifecycle Netty bootstrap base class
- `lightweight/src/main/java/.../server/MqttSessionHandlerFactory.java` - Factory centralizing 11-dep MqttSessionHandler creation
- `lightweight/src/main/java/.../server/MqttTcpServerBootstrap.java` - Refactored to extend AbstractServerBootstrap (phase=0)
- `lightweight/src/main/java/.../server/MqttChannelInitializer.java` - Refactored to use factory (3 fields)
- `lightweight/src/main/java/.../server/tls/MqttSslServerBootstrap.java` - Refactored to extend AbstractServerBootstrap (phase=1)
- `lightweight/src/main/java/.../server/tls/MqttSslChannelInitializer.java` - Refactored to use factory (4 fields)
- `lightweight/src/main/java/.../server/wshandler/WsBinaryFrameHandler.java` - Binary WS frame to ByteBuf passthrough (retain())
- `lightweight/src/main/java/.../server/wshandler/WsByteBufEncoder.java` - ByteBuf to BinaryWebSocketFrame encoder (ReferenceCountUtil.retain)
- `lightweight/src/main/java/.../server/wshandler/WsContinuationFrameHandler.java` - Continuation WS frame passthrough (retain())
- `lightweight/src/main/java/.../server/wshandler/WsTextFrameHandler.java` - Text WS frame → disconnect (trimmed TBMQ version)
- `lightweight/src/main/java/.../config/WsConfiguration.java` - WS config (enabled=true, port=8084, subProtocols, maxContentLength)
- `lightweight/src/main/java/.../config/WssConfiguration.java` - WSS config (enabled=false, port=8085, subProtocols, maxContentLength)
- `lightweight/src/main/resources/tbmq-lightweight.yml` - Added tbmq.ws.* and tbmq.wss.* sections with env var defaults

## Decisions Made
- AbstractServerBootstrap does NOT override `getPhase()` or `isAutoStartup()` — each concrete subclass owns its lifecycle phase and startup conditions (TLS checks `tlsConfig.isEnabled()`, TCP always starts)
- WsTextFrameHandler trimmed from TBMQ: the `MqttSessionHandler.ADDRESS` channel attribute doesn't exist in lightweight's MqttSessionHandler, so the simplified version logs `ctx.channel().remoteAddress()` directly
- WsConfiguration uses `@Value` annotations (not `@ConfigurationProperties`) to match the TlsConfiguration pattern already established in lightweight

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- AbstractServerBootstrap, MqttSessionHandlerFactory, 4 WS frame handlers, WsConfiguration, WssConfiguration are all ready
- Plan 05-02 can implement MqttWsServerBootstrap and MqttWssServerBootstrap by extending AbstractServerBootstrap
- WS channel initializers (MqttWsChannelInitializer, MqttWssChannelInitializer) can use MqttSessionHandlerFactory + the 4 new frame handlers

---
*Phase: 05-websocket-transport*
*Completed: 2026-04-09*
