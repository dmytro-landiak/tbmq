---
phase: 02-core-protocol-mqtt-3-1-1
plan: 01
subsystem: infra
tags: [actor-framework, session, mqtt, netty, forkjoinpool, smartlifecycle]

requires:
  - phase: 01-foundation
    provides: "Spring Boot app with RocksDB, Netty TCP server, Caffeine cache"

provides:
  - "Actor framework (19 files) ported to o.t.m.b.lightweight.actors package"
  - "DefaultTbActorSystem with ForkJoinPool client-dispatcher"
  - "ActorSystemConfiguration as SmartLifecycle at phase -1"
  - "Session types: ClientSessionCtx, DisconnectReasonType, SessionState"
  - "Protocol types: PublishMsg, PacketIdAllocator, MqttConfiguration"
  - "YAML config properties for actors and MQTT protocol limits"

affects:
  - 02-03-mqtt-connect-handler
  - 02-04-mqtt-publish-subscribe
  - 02-05-mqtt-qos-persistence

tech-stack:
  added: []
  patterns:
    - "SmartLifecycle phase -1 for actor system startup ordering"
    - "ForkJoinPool single dispatcher per D-03 (one 'client-dispatcher')"
    - "ConcurrentLinkedQueue + AtomicBoolean busy flag for lock-free mailbox"
    - "Per-session PacketIdAllocator (not shared across clients)"
    - "DisconnectReasonType.allowsLastWillOnDisconnect() for LWT gating"

key-files:
  created:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/DefaultTbActorSystem.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/TbActorMailbox.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/TbActorSystem.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/MsgType.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/TbActorMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/ActorSystemConfiguration.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/ClientSessionCtx.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/DisconnectReasonType.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/SessionState.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/PublishMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/packet/PacketIdAllocator.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/MqttConfiguration.java
  modified:
    - lightweight/src/main/resources/tbmq-lightweight.yml

key-decisions:
  - "TbTypeActorId uses plain String for type (not ActorType enum) — lightweight has no shared data module with ActorType"
  - "ActorSystemConfiguration uses @Value injection (not @ConfigurationProperties) to avoid circular bean dependency with DefaultTbActorSystem @Bean"
  - "DefaultTbActorSystem constructor takes no ActorStatsManager — lightweight has no actor stats infrastructure"
  - "MsgType simplified to 13 Phase 2 types only — no DEVICE_* or INTEGRATION_* entries"

patterns-established:
  - "Actor pattern: all actor classes in flat o.t.m.b.lightweight.actors package"
  - "Session pattern: ClientSessionCtx is created per TCP connection with new UUID sessionId"
  - "LWT pattern: check DisconnectReasonType.allowsLastWillOnDisconnect() before publishing LWT"

requirements-completed: [PROTO-01, PROTO-07]

duration: 6min
completed: 2026-04-06
---

# Phase 02 Plan 01: Actor Framework and Session Infrastructure Summary

**Actor framework ported (19 files) with ForkJoinPool dispatcher at SmartLifecycle phase -1, plus session/protocol types (ClientSessionCtx, DisconnectReasonType, PacketIdAllocator) providing the stable contract for Plans 03-05**

## Performance

- **Duration:** 6 min
- **Started:** 2026-04-06T12:49:49Z
- **Completed:** 2026-04-06T12:55:22Z
- **Tasks:** 2
- **Files modified:** 26 (20 created + 1 modified in Task 1; 6 created in Task 2)

## Accomplishments

- Ported 19 actor framework files from `common/actor` into `o.t.m.b.lightweight.actors`, eliminating the `ActorStatsManager` dependency and inlining executor shutdown utilities (no Guava)
- Created `ActorSystemConfiguration` as `SmartLifecycle` at phase -1 — starts after RocksDB and before Netty; creates `client-dispatcher` backed by `ForkJoinPool(availableProcessors)`
- Simplified `MsgType` enum to 13 Phase 2-only types (removed all `DEVICE_*`, `INTEGRATION_*`, and cluster-specific types)
- Created all session/protocol types: `ClientSessionCtx` with QoS tracking maps, `DisconnectReasonType` (LWT-aware), `SessionState`, `PublishMsg` (@Builder), `PacketIdAllocator`, `MqttConfiguration`
- All 51 existing tests pass (30 pre-existing skips, 0 failures) with actor system starting and stopping cleanly

## Task Commits

1. **Task 1: Port actor framework** - `dc8a1a6ea` (feat)
2. **Task 2: Create session and protocol types** - `d947d4b80` (feat)

## Files Created/Modified

- `lightweight/actors/DefaultTbActorSystem.java` (241 lines) — actor registry, dispatcher management, parent-child tracking
- `lightweight/actors/TbActorMailbox.java` (236 lines) — ConcurrentLinkedQueue + AtomicBoolean busy flag mailbox
- `lightweight/actors/TbActorSystem.java` — interface with full API including filterChildren/broadcastToChildren
- `lightweight/actors/MsgType.java` — 13 Phase 2 MQTT message types only
- `lightweight/actors/TbActorMsg.java` — interface with getMsgType() and onTbActorStopped() default
- `lightweight/actors/TbTypeActorId.java` — String-based type+id pair (no ActorType enum dependency)
- `lightweight/actors/TbActorSystemSettings.java` — @Builder POJO with defaults (throughput=10, scheduler=1, maxInit=10)
- `lightweight/config/ActorSystemConfiguration.java` — SmartLifecycle phase=-1, ForkJoinPool dispatcher
- `lightweight/session/ClientSessionCtx.java` — per-connection context with all QoS maps and PacketIdAllocator
- `lightweight/session/DisconnectReasonType.java` — enum with allowsLastWillOnDisconnect(); ON_CONFLICTING_SESSIONS=false
- `lightweight/session/SessionState.java` — 4-state enum (INITIALIZING, CONNECTED, DISCONNECTING, DISCONNECTED)
- `lightweight/service/mqtt/PublishMsg.java` — @Data @Builder with topicName, qos, payload, retain, dup, packetId
- `lightweight/packet/PacketIdAllocator.java` — ConcurrentHashMap.newKeySet() for O(1) ID tracking
- `lightweight/config/MqttConfiguration.java` — @ConfigurationProperties(prefix="tbmq.mqtt") with 3 limits
- `lightweight/resources/tbmq-lightweight.yml` — added tbmq.actors.* and tbmq.mqtt.* config blocks

## Decisions Made

- **TbTypeActorId uses String type (not ActorType enum):** The original uses `org.thingsboard.mqtt.broker.common.data.id.ActorType` from the `common/data` module which is not a dependency of lightweight. Using a plain `String` type avoids adding the entire common/data module as a dependency.
- **ActorSystemConfiguration uses @Value:** Using `@Value` for the 3 actor properties instead of `@ConfigurationProperties` avoids a potential circular dependency where the `TbActorSystem` @Bean requires settings that come from another bean.
- **No ActorStatsManager in lightweight:** The metrics bean would require adding stats infrastructure. Phase 1 metrics are handled by `BrokerMetricsService`. Actor-level stats can be added in R2 if needed.
- **MsgType simplified to 13 types:** Only the types needed by Plans 02-05 are included. DEVICE_*, INTEGRATION_*, and cluster coordination types are not needed in single-node mode.

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None.

## Known Stubs

None — all created files are complete implementations.

## Next Phase Readiness

- Actor framework is ready: Plans 03-05 can create ClientActors using `DefaultTbActorSystem.createRootActor("client-dispatcher", creator)`
- Session types provide stable contract: `ClientSessionCtx` holds channel, state, and QoS maps ready for CONNECT handler (Plan 03)
- `DisconnectReasonType.allowsLastWillOnDisconnect()` is the correct gate for LWT logic
- `PacketIdAllocator` is per-session — Plans 04-05 should create one per `ClientSessionCtx` (already wired in constructor)
- YAML properties are bound — `MqttConfiguration` is available for injection in Netty handlers

---
*Phase: 02-core-protocol-mqtt-3-1-1*
*Completed: 2026-04-06*
