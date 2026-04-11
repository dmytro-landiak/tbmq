---
phase: 06-mqtt-5-0
plan: 02
subsystem: mqtt-protocol
tags: [mqtt5, netty, topic-alias, shared-subscriptions, reason-codes, receive-maximum, properties]

# Dependency graph
requires:
  - phase: 06-01
    provides: "MqttPropertiesUtil, MqttReasonCodeResolver, TopicAliasCtx, Mqtt5Configuration, extended domain models (PublishMsg+properties, RetainedMsg+createdTime, DeliverMsg+subscriptionId, Subscription+shareName/subscriptionId, ClientSessionCtx+mqttVersion/topicAliasCtx/receiveMaximum), MqttMessageGenerator MQTT 5.0 overloads"
provides:
  - "MqttSessionHandler: MQTT version detection, topic alias inbound resolution, properties extraction from PUBLISH, broker-initiated DISCONNECT with reason code"
  - "ClientActor: version-aware CONNACK with TopicAliasMax/ReceiveMax/SessionExpiry properties, reason codes in all ACKs, shared subscription parsing, subscription identifier propagation, server-side topic alias allocation, Receive Maximum enforcement"
  - "DefaultMsgDispatcherService: shared subscription round-robin delivery grouped by share name"
  - "DefaultRetainedMsgService: lazy expiry filtering of retained messages on getRetainedMessages"
affects: [06-03, mqtt5-testing, lightweight-docker]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Version-aware ACK pattern: MqttReasonCodeResolver returns null for 3.1.1, reason code for 5.0 — callers use the overloaded MqttMessageGenerator method uniformly"
    - "Shared subscription prefix stripping: $share/group/topic stored in trie as topic, shareName stored on Subscription — dispatcher groups by shareName for round-robin"
    - "Lazy expiry cleanup: getRetainedMessages filters expired entries via removeIf and deletes from trie in-place"
    - "Properties copy-before-release: inbound PUBLISH properties copied via copyPublishPropertiesToDeliver before ByteBuf is released in channelRead finally block"

key-files:
  created: []
  modified:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandlerFactory.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActorCreator.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/DefaultRetainedMsgService.java

key-decisions:
  - "MqttMessageIdAndPropertiesVariableHeader instanceof pattern used to safely extract SUBSCRIBE properties — avoids cast exception on MQTT 3.1.1 clients whose variable header is plain MqttMessageIdVariableHeader"
  - "MqttReasonCodes.SubAck uses byteValue() not value() — Netty 4.1.122 API difference from plan pseudocode"
  - "Receive Maximum check in processDeliver uses current outboundQos1.size() + outboundQos2.size() as in-flight count — simple and correct for single-node clean-session broker"
  - "Server-side topic alias added to deliverProps via MqttPropertiesUtil.addTopicAliasToProps — always sends topic + alias (client stores the mapping per spec)"
  - "getRetainedMessages returns mutable ArrayList from trie get() — removeIf safe without defensive copy"

patterns-established:
  - "Shared subscription delivery: dispatcher groups subscriptions by shareName, uses ConcurrentHashMap<String, AtomicInteger> sharedGroupCounters with Math.floorMod for fair round-robin"
  - "MQTT version branching: sessionCtx.getMqttVersion() == MqttVersion.MQTT_5 guard before any 5.0-specific logic, 3.1.1 path unchanged"

requirements-completed: [PROTO-08, PROTO-09, PROTO-10]

# Metrics
duration: 45min
completed: 2026-04-11
---

# Phase 6 Plan 02: MQTT 5.0 Handler-Actor-Dispatcher Chain Summary

**Full MQTT 5.0 wire protocol wired through handler-actor-dispatcher: version negotiation, topic alias resolution, shared subscriptions with round-robin, subscription identifiers, reason codes in all ACKs, and receive maximum flow control**

## Performance

- **Duration:** ~45 min
- **Started:** 2026-04-11T09:30:00Z
- **Completed:** 2026-04-11T10:15:33Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments

- MqttSessionHandler detects MQTT version from CONNECT, initializes TopicAliasCtx and ReceiveMaximum on ClientSessionCtx, resolves inbound topic aliases before passing to actor, copies properties from PUBLISH before ByteBuf release, sends DISCONNECT with reason code for broker-initiated closes on MQTT 5.0 clients
- ClientActor sends CONNACK with TopicAliasMax/ReceiveMax/SessionExpiry=0 for MQTT 5.0 clients, uses version-aware reason codes (via MqttReasonCodeResolver) in all PUBACK/PUBREC/SUBACK, parses $share/group/topic prefix for shared subscriptions, propagates subscription identifiers to DeliverMsg, allocates server-side topic aliases on outbound PUBLISH, enforces Receive Maximum flow control, delivers retained messages with expiry check and skips retained for shared subscriptions
- DefaultMsgDispatcherService groups matching subscriptions by shareName and delivers to one member per group via round-robin (ConcurrentHashMap counters with Math.floorMod)
- DefaultRetainedMsgService filters expired retained messages on every getRetainedMessages call with lazy trie cleanup

## Task Commits

1. **Task 1: MqttSessionHandler version detection, topic alias resolution, properties extraction** - `9d431504e` (feat)
2. **Task 2: ClientActor version-aware CONNACK/ACKs, topic aliases, shared subscriptions, dispatcher routing** - `affca1489` (feat)

## Files Created/Modified

- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java` - Added Mqtt5Configuration dep, MQTT version detection, TopicAliasCtx + ReceiveMax init, topic alias inbound resolution, properties copy, broker-initiated DISCONNECT with reason code
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandlerFactory.java` - Added Mqtt5Configuration field and passes it to MqttSessionHandler
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java` - Full MQTT 5.0 awareness: version-aware CONNACK, reason codes in ACKs, shared subscription parsing, subscription ID extraction, server-side topic aliases, Receive Maximum enforcement, properties forwarding
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActorCreator.java` - Added Mqtt5Configuration field and passes it to ClientActor
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java` - Shared subscription grouping and round-robin delivery, subscriptionId forwarding in DeliverMsg
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/DefaultRetainedMsgService.java` - Expired retained message filtering with lazy trie cleanup

## Decisions Made

- Used `instanceof MqttMessageIdAndPropertiesVariableHeader` pattern guard for SUBSCRIBE properties extraction — avoids ClassCastException on MQTT 3.1.1 clients where the variable header is the base type
- `MqttReasonCodes.SubAck` uses `byteValue()` (not `value()`) — Netty 4.1.122 API; plan pseudocode used incorrect method name, fixed at compile time
- Server-side topic aliases always include full topic name alongside alias on outbound PUBLISH — simplest correct behavior; client can optimize per spec

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] ClientActorCreator updated alongside MqttSessionHandler (Task 1)**
- **Found during:** Task 1 (MqttSessionHandler compilation)
- **Issue:** MqttSessionHandler.processConnect() passes Mqtt5Configuration to ClientActorCreator constructor, but ClientActorCreator didn't have the field yet — compilation failure
- **Fix:** Added Mqtt5Configuration field to ClientActorCreator and updated createActor() to pass it to ClientActor — this is sequentially required and was planned for Task 2 anyway
- **Files modified:** ClientActorCreator.java, ClientActor.java (field added)
- **Verification:** mvn compile -q passed
- **Committed in:** affca1489 (Task 2 commit)

**2. [Rule 1 - Bug] MqttReasonCodes.SubAck uses byteValue() not value()**
- **Found during:** Task 2 (ClientActor compilation)
- **Issue:** Plan pseudocode used `.value()` on `MqttReasonCodes.SubAck` enum constants; Netty 4.1.122 exposes `byteValue()` instead
- **Fix:** Replaced `.value() & 0xFF` with `.byteValue() & 0xFF` on both UNSPECIFIED_ERROR and NOT_AUTHORIZED usages
- **Files modified:** ClientActor.java
- **Verification:** mvn compile -q passed
- **Committed in:** affca1489 (Task 2 commit)

**3. [Rule 1 - Bug] SUBSCRIBE properties require instanceof cast to MqttMessageIdAndPropertiesVariableHeader**
- **Found during:** Task 2 (ClientActor compilation)
- **Issue:** Plan pseudocode called `.properties()` directly on `msg.getMqttSubscribeMessage().variableHeader()`, which returns `MqttMessageIdVariableHeader` — that type doesn't have `properties()`. Only the subclass `MqttMessageIdAndPropertiesVariableHeader` does.
- **Fix:** Used `instanceof MqttMessageIdAndPropertiesVariableHeader propsHeader` pattern match guard before calling `propsHeader.properties()`
- **Files modified:** ClientActor.java
- **Verification:** mvn compile -q passed
- **Committed in:** affca1489 (Task 2 commit)

---

**Total deviations:** 3 auto-fixed (1 blocking, 2 bugs from API mismatch in plan pseudocode)
**Impact on plan:** All fixes necessary for compilation correctness. No scope creep. Behavior matches plan intent.

## Issues Encountered

None beyond the three auto-fixed compile errors from Netty API differences in plan pseudocode.

## Known Stubs

None — all functionality is wired. Properties flow from inbound PUBLISH through dispatch to outbound PUBLISH delivery.

## Threat Flags

No new security-relevant surfaces introduced beyond what was documented in the plan's threat model (T-06-04 through T-06-09). All mitigations applied:
- T-06-04: session expiry always 0 in CONNACK, DISCONNECT expiry ignored
- T-06-05: topic alias validation via TopicAliasCtx.validateTopicAlias(), disconnect on invalid
- T-06-07: malformed $share topic returns SUBACK failure code, does not crash
- T-06-08: Receive Maximum enforcement in processDeliver drops excess in-flight messages

## Next Phase Readiness

- Broker is functionally MQTT 5.0 capable — version negotiation, CONNACK properties, reason codes, topic aliases, shared subscriptions, subscription identifiers, message expiry all implemented
- Plan 03 (MQTT 5.0 verification/testing) can proceed against this implementation
- No blockers

---
*Phase: 06-mqtt-5-0*
*Completed: 2026-04-11*

## Self-Check: PASSED

- All 6 modified files exist on disk
- Task 1 commit `9d431504e` exists in git log
- Task 2 commit `affca1489` exists in git log
- `mvn compile -q` in lightweight/ passes with no errors
