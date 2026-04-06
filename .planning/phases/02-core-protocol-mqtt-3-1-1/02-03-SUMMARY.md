---
phase: 02-core-protocol-mqtt-3-1-1
plan: 03
subsystem: mqtt-protocol
tags: [netty, mqtt, actor-system, connect, disconnect, keep-alive, ping, session]

# Dependency graph
requires:
  - phase: 02-core-protocol-mqtt-3-1-1
    provides: actor system (TbActorSystem, DefaultTbActorSystem, client-dispatcher), ClientSessionCtx, SessionState, DisconnectReasonType, MsgType
  - phase: 01-foundation
    provides: Netty server bootstrap, MqttChannelInitializer, ConnectionCountHandler, RocksDB storage, Spring Boot context

provides:
  - Netty MQTT pipeline: MqttDecoder + MqttEncoder + MqttSessionHandler in correct order
  - MqttSessionHandler routing CONNECT/DISCONNECT/PINGREQ to actor system
  - ClientActor processing CONNECT (CONNACK, keep-alive), DISCONNECT, PING
  - ClientSessionRegistry (ConcurrentHashMap) for active session tracking
  - MqttMessageGenerator interface and DefaultMqttMessageGenerator (CONNACK, PINGRESP)
  - Actor message types: SessionInitMsg, MqttConnectMsg, MqttDisconnectMsg, PingMsg, SessionCloseMsg
  - Integration tests: CONNECT, keep-alive expiry, PING, session state

affects: [02-04-publish-subscribe, 02-05-lwt-retained, future-auth-plans]

# Tech tracking
tech-stack:
  added: [eclipse-paho-mqttv3 (tests), awaitility (tests), MqttDecoder, MqttEncoder, IdleStateHandler (keep-alive)]
  patterns: [one-actor-per-client, fire-and-forget-writeAndFlush, idle-handler-replace-on-connect, raw-socket-testing-for-protocol-edge-cases]

key-files:
  created:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActorCreator.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/SessionInitMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/MqttConnectMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/MqttDisconnectMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/PingMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/SessionCloseMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/MqttMessageGenerator.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/DefaultMqttMessageGenerator.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/ClientSessionRegistry.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/DefaultClientSessionRegistry.java
  modified:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttChannelInitializer.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttConnectIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttKeepAliveIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttSessionIntegrationTest.java

key-decisions:
  - "MqttSessionHandler does NOT directly reply to clients — all responses go through actor system via tell()"
  - "IdleStateHandler is replaced (pipeline.replace) from actor's eventLoop thread after CONNECT to set 1.5x keep-alive timeout"
  - "SessionCloseMsg reuses DISCONNECT_MSG type — actor switches on message class at runtime to detect channel-already-closed case"
  - "PingMsg is a singleton (INSTANCE pattern) — no mutable state in PING messages"
  - "Empty clientId with cleanSession=true receives broker-assigned UUID — Paho receives CONNACK and reports connected"
  - "Raw socket test with buildMqttConnectPacket() used for keep-alive expiry test — Paho cannot simulate an idle client"

patterns-established:
  - "Pattern: All per-channel handlers (MqttSessionHandler, MqttDecoder, IdleStateHandler) are NOT @Sharable — new instance per channel in initChannel()"
  - "Pattern: ReferenceCountUtil.safeRelease(msg) in finally block of channelRead to prevent ByteBuf leaks under PARANOID leak detection"
  - "Pattern: Actor messages carry minimal data — large objects (MqttConnectMessage) passed by reference, not copied"
  - "Pattern: ClientActor.process() never blocks — all writes via fire-and-forget writeAndFlush()"

requirements-completed: [PROTO-01, PROTO-04, PROTO-07, TRAN-01]

# Metrics
duration: 6min
completed: 2026-04-06
---

# Phase 02 Plan 03: MQTT CONNECT/CONNACK Flow and Keep-Alive Summary

**Netty MQTT pipeline (MqttDecoder + MqttEncoder + MqttSessionHandler) wired through actor system enabling full CONNECT/CONNACK/PING/DISCONNECT flow with 1.5x keep-alive enforcement**

## Performance

- **Duration:** 6 min
- **Started:** 2026-04-06T12:58:58Z
- **Completed:** 2026-04-06T13:04:15Z
- **Tasks:** 3
- **Files modified:** 16

## Accomplishments

- Paho MQTT 3.1.1 client can connect to the broker, receive CONNACK(return_code=0), send PINGREQ, receive PINGRESP, and disconnect cleanly
- Keep-alive enforcement works: 1.5x timeout fires after silence, keepAlive=0 disables timeout entirely
- Session registry tracks active connections via ConcurrentHashMap; old sessions closed on takeover (stub) or disconnect
- All 7 newly-enabled integration tests pass; all 51 total tests pass (22 remain @Disabled for Plans 04/05)

## Task Commits

1. **Task 1: Netty pipeline and MqttSessionHandler** - `da2bb570d` (feat)
2. **Task 2: ClientActor CONNECT/CONNACK flow and session registry** - `38cbb115e` (feat)
3. **Task 3: Enable and pass integration tests** - `7f07f3e10` (feat)

## Files Created/Modified

- `server/MqttChannelInitializer.java` — updated with MqttDecoder, MqttEncoder, MqttSessionHandler
- `server/MqttSessionHandler.java` — per-channel MQTT router to actor system
- `actors/client/ClientActor.java` — processes CONNECT/DISCONNECT/PING with actor pattern
- `actors/client/ClientActorCreator.java` — factory for ClientActor instances
- `actors/client/msg/SessionInitMsg.java` — carries ClientSessionCtx to new actor
- `actors/client/msg/MqttConnectMsg.java` — carries MqttConnectMessage + ClientSessionCtx
- `actors/client/msg/MqttDisconnectMsg.java` — carries DisconnectReasonType + reason
- `actors/client/msg/PingMsg.java` — singleton, no payload
- `actors/client/msg/SessionCloseMsg.java` — channel-closed notification
- `service/mqtt/MqttMessageGenerator.java` — interface: createConnAck, createPingResp
- `service/mqtt/DefaultMqttMessageGenerator.java` — @Service using MqttMessageBuilders
- `session/ClientSessionRegistry.java` — interface: register/remove/get/count
- `session/DefaultClientSessionRegistry.java` — @Service with ConcurrentHashMap
- `mqtt/MqttConnectIntegrationTest.java` — enabled 3 tests (2 kept @Disabled for raw socket edge cases)
- `mqtt/MqttKeepAliveIntegrationTest.java` — enabled all 3 tests including keep-alive expiry via raw socket
- `mqtt/MqttSessionIntegrationTest.java` — enabled both session state tests

## Decisions Made

- `SessionCloseMsg` reuses `DISCONNECT_MSG` type to avoid adding a new `SESSION_CLOSE_MSG` type to the enum; actor distinguishes by message class `instanceof` check
- `PingMsg` is a singleton (`INSTANCE`) — PINGREQ carries no data per spec, so no allocation needed per message
- Raw socket approach (`buildMqttConnectPacket`) used for keep-alive expiry test — Paho auto-sends PINGREQ so it can't simulate an idle client
- `testConnect_secondConnectOnSameChannel_disconnects` kept @Disabled — requires raw socket test (Paho doesn't allow sending 2nd CONNECT)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Created ClientSessionRegistry, DefaultClientSessionRegistry, ClientActorCreator, and ClientActor during Task 1**
- **Found during:** Task 1 (MqttSessionHandler compilation)
- **Issue:** `MqttSessionHandler` imports `ClientSessionRegistry` and `ClientActorCreator` from Task 2's scope; compilation fails without them
- **Fix:** Created all four files from Task 2 before the compilation check at the end of Task 1
- **Files modified:** All four Task 2 files created
- **Verification:** `mvn -f lightweight/pom.xml compile -q` exits 0 after both tasks' files exist
- **Committed in:** `38cbb115e` (Task 2 commit, post-compilation-fix)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Task 2 files were created during Task 1 to unblock compilation; they were committed as Task 2. No scope changes.

## Issues Encountered

None beyond the compilation ordering issue documented above.

## Known Stubs

- **LWT processing** — `ClientActor.processConnect()` logs "LWT configured — will be processed in Plan 05" when `willFlag=true`; no actual LWT message is stored or published. Wired in Plan 05.
- **Client takeover** — `ClientActor.processConnect()` closes old channel when `oldSession != null` but does not send any notification or process LWT. Full takeover logic in Plan 05.
- **PUBLISH/SUBSCRIBE/UNSUBSCRIBE/PUBxxx handlers** in `MqttSessionHandler` — log "will be handled in Plan 04"; no message routing to subscribers yet.

## Next Phase Readiness

- Plan 04 (publish/subscribe) can now add `PUBLISH_MSG`, `SUBSCRIBE_MSG`, `UNSUBSCRIBE_MSG` handling in `ClientActor.process()` and extend `MqttMessageGenerator` with `createSubAck`, `createUnsubAck`, `createPublish`, etc.
- Plan 05 (LWT/retained) fills the LWT and takeover stubs in `ClientActor`
- `ClientSessionRegistry` is injected everywhere needed; Plans 04/05 can call `getSession()` for pub/sub routing

## Self-Check: PASSED

All files created/modified exist on disk. All task commits verified in git log:
- `da2bb570d` — Task 1: Netty pipeline + MqttSessionHandler
- `38cbb115e` — Task 2: ClientActor + session registry
- `7f07f3e10` — Task 3: Integration tests enabled and passing

---
*Phase: 02-core-protocol-mqtt-3-1-1*
*Completed: 2026-04-06*
