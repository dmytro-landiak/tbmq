---
phase: 02-core-protocol-mqtt-3-1-1
plan: 05
subsystem: mqtt-protocol
tags: [mqtt, retained-messages, lwt, last-will, client-takeover, netty, actor-model]

# Dependency graph
requires:
  - phase: 02-core-protocol-mqtt-3-1-1
    plan: 04
    provides: "SUBSCRIBE/UNSUBSCRIBE handling, QoS 0/1/2 delivery, SubscriptionRegistry"

provides:
  - "RetainedMsgService: ConcurrentHashMap-backed in-memory retained message store"
  - "LastWillService: ConcurrentHashMap-backed LWT store keyed by session UUID"
  - "ClientActor: full MQTT 3.1.1 retained message, LWT, and client takeover support"
  - "10 integration tests: retained (4), LWT (3), takeover (3) — all passing"

affects: [03-tls-websocket, 04-auth-acl, Phase 2 completion]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "LWT keyed by session UUID (not clientId) for correct takeover isolation"
    - "Raw socket MQTT connect for testing ungraceful disconnect (bypasses Paho DISCONNECT packet)"
    - "TbActorNotRegisteredException caught in channelInactive for graceful post-takeover cleanup"

key-files:
  created:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/RetainedMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/RetainedMsgService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/DefaultRetainedMsgService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/will/WillMessage.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/will/LastWillService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/will/DefaultLastWillService.java
  modified:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActorCreator.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttChannelInitializer.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/AbstractMqttIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttRetainedMsgIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttLwtIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttClientTakeoverTest.java

key-decisions:
  - "LWT delivery uses raw socket approach for ungraceful disconnect tests — Paho's disconnectForcibly interferes with keepalive timer causing 30s delays; raw socket close is immediate and deterministic"
  - "LWT keyed by session UUID (not clientId) — enables correct isolation during takeover: old session UUID is different from new session UUID, ensuring old LWT is suppressed without affecting new session's LWT"
  - "TbActorNotRegisteredException caught in MqttSessionHandler.channelInactive — old session channel fires channelInactive after being closed during takeover, but actor is already stopped; graceful handling prevents spurious ERROR logs"
  - "Retained message delivery on SUBSCRIBE sends retain=true flag — per MQTT 3.1.1 spec, retained messages delivered to new subscribers must have the retain flag set to distinguish from live publishes"
  - "QoS 2 retained message handling deferred to PUBREL — retain flag processing for QoS 2 PUBLISH happens at PUBREL time (same as delivery), consistent with exactly-once semantics"

patterns-established:
  - "LWT pattern: storeWill(sessionId, will) at CONNECT; removeWill on ungraceful disconnect (deliver); removeWillWithoutDelivery on clean disconnect or takeover"
  - "Retained message pattern: setRetainedMessage/clearRetainedMessage on PUBLISH; getRetainedMessage on SUBSCRIBE for each topic filter"
  - "Takeover pattern: lastWillService.removeWillWithoutDelivery(oldSession.getSessionId()) before closing old channel — must precede channel.close()"

requirements-completed: [PROTO-05, PROTO-06, PROTO-11]

# Metrics
duration: 13min
completed: 2026-04-07
---

# Phase 02 Plan 05: Retained Messages, LWT, and Client Takeover Summary

**ConcurrentHashMap-backed retained messages and LWT service with full client takeover support, completing MQTT 3.1.1 spec compliance for Phase 2**

## Performance

- **Duration:** 13 min
- **Started:** 2026-04-07T18:22:50Z
- **Completed:** 2026-04-07T18:35:20Z
- **Tasks:** 2
- **Files modified:** 14

## Accomplishments
- RetainedMsgService (interface + DefaultRetainedMsgService backed by ConcurrentHashMap) and LastWillService (interface + DefaultLastWillService backed by ConcurrentHashMap keyed by session UUID)
- ClientActor fully wired: retained message store/clear/deliver on PUBLISH and SUBSCRIBE; LWT store at CONNECT, deliver on ungraceful disconnect, suppress on clean disconnect and client takeover
- Client takeover: old session channel closed with LWT suppressed; new session fully functional immediately after
- 10 integration tests implemented and passing: 4 retained message tests, 3 LWT tests (using raw socket for deterministic ungraceful disconnect), 3 client takeover tests

## Task Commits

1. **Task 1: Create RetainedMsgService and LastWillService** - `04206e493` (feat)
2. **Task 2: Wire into ClientActor and implement integration tests** - `9637ec9a3` (feat)

## Files Created/Modified
- `service/mqtt/retain/RetainedMsg.java` - Immutable retained message record (@Data @Builder)
- `service/mqtt/retain/RetainedMsgService.java` - Service interface (set/clear/get)
- `service/mqtt/retain/DefaultRetainedMsgService.java` - ConcurrentHashMap<String, RetainedMsg>
- `service/mqtt/will/WillMessage.java` - Immutable LWT record (@Data @Builder)
- `service/mqtt/will/LastWillService.java` - Service interface (store/removeWill/removeWillWithoutDelivery)
- `service/mqtt/will/DefaultLastWillService.java` - ConcurrentHashMap<UUID, WillMessage>
- `actors/client/ClientActor.java` - Full retained, LWT, and takeover implementation
- `actors/client/ClientActorCreator.java` - Passes RetainedMsgService and LastWillService to ClientActor
- `server/MqttChannelInitializer.java` - Injects new services via Spring DI
- `server/MqttSessionHandler.java` - Passes services to ClientActorCreator; catches TbActorNotRegisteredException in channelInactive
- `mqtt/AbstractMqttIntegrationTest.java` - clients list exposed as protected for subclass use
- `mqtt/MqttRetainedMsgIntegrationTest.java` - 4 tests: deliver on subscribe, clear on empty payload, retain flag, QoS downgrade
- `mqtt/MqttLwtIntegrationTest.java` - 3 tests: ungraceful disconnect (raw socket), keep-alive expiry (raw socket), clean disconnect (suppressed)
- `mqtt/MqttClientTakeoverTest.java` - 3 tests: new session replaces old, LWT suppressed on takeover, new session functional

## Decisions Made
- Used raw socket CONNECT packet with will fields for LWT ungraceful disconnect tests — Paho's `disconnectForcibly(0, 0)` caused 30-70s delays due to internal keepalive timer; raw socket close is immediate and deterministic
- LWT keyed by session UUID (UUID per TCP connection) ensures takeover isolation — old session and new session have different UUIDs, so their LWT entries don't interfere
- Caught `TbActorNotRegisteredException` in `channelInactive` to prevent spurious ERROR logs when displaced session's channel fires close event after the actor was already replaced

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Graceful handling of TbActorNotRegisteredException in channelInactive**
- **Found during:** Task 2 (integration tests)
- **Issue:** After client takeover, the old session's channel fires `channelInactive` — `MqttSessionHandler` tries to tell the actor a `SessionCloseMsg`, but the actor was already replaced/stopped, causing `TbActorNotRegisteredException` which propagated through `exceptionCaught` as an ERROR log
- **Fix:** Added `try/catch TbActorNotRegisteredException` in `channelInactive` with a DEBUG log instead
- **Files modified:** `server/MqttSessionHandler.java`
- **Verification:** No ERROR logs in takeover test; all tests pass
- **Committed in:** 9637ec9a3 (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 — bug in takeover cleanup path)
**Impact on plan:** Minor fix — prevents noisy error logs during client takeover. No scope creep.

## Issues Encountered
- Paho `disconnectForcibly(0, 0)` does not close the socket in a way the broker detects within the 10-second test window — switched to raw socket approach (same pattern already used in `MqttKeepAliveIntegrationTest`)

## Next Phase Readiness
- Phase 2 MQTT 3.1.1 protocol surface is complete: CONNECT/CONNACK, PUBLISH/QoS, SUBSCRIBE/UNSUBSCRIBE, PING, DISCONNECT, retained messages, LWT, client takeover
- All 51 tests pass (3 skipped are from future plans)
- Ready for Phase 3: TLS termination and MQTT over WebSocket

---
*Phase: 02-core-protocol-mqtt-3-1-1*
*Completed: 2026-04-07*

## Self-Check: PASSED

- RetainedMsg.java: FOUND
- RetainedMsgService.java: FOUND
- DefaultRetainedMsgService.java: FOUND
- WillMessage.java: FOUND
- LastWillService.java: FOUND
- DefaultLastWillService.java: FOUND
- Commit 04206e493: FOUND
- Commit 9637ec9a3: FOUND
