---
phase: 02-core-protocol-mqtt-3-1-1
plan: "04"
subsystem: messaging
tags: [mqtt, pubsub, subscribe, qos, netty, actor]

requires:
  - phase: 02-core-protocol-mqtt-3-1-1
    provides: ClientActor with CONNECT/DISCONNECT/PING, MqttSessionHandler Netty pipeline, ClientSessionCtx with QoS tracking maps, PacketIdAllocator, MsgType enum with all Phase 2 types

provides:
  - SubscriptionRegistry interface and DefaultSubscriptionRegistry (ConcurrentHashMap exact-match, Phase 2 D-06)
  - Subscription domain class with clientId, qos, sessionCtx for inline delivery
  - All 8 actor message types: MqttPublishMsg, MqttSubscribeMsg, MqttUnsubscribeMsg, MqttPubAckMsg, MqttPubRecMsg, MqttPubRelMsg, MqttPubCompMsg, DeliverMsg
  - MqttMessageGenerator extended with 7 new methods: createSubAck, createUnsubAck, createPublish, createPubAck, createPubRec, createPubRel, createPubComp
  - ClientActor handles SUBSCRIBE/UNSUBSCRIBE/PUBLISH/PUBACK/PUBREC/PUBREL/PUBCOMP
  - Inline QoS 0/1/2 delivery with QoS downgrade (min(publishQos, subscriptionQos))
  - QoS 2 inbound deduplication via inboundQos2 map (Pitfall 2 prevention)
  - MqttSessionHandler routes all MQTT message types including payload ByteBuf copy before release
  - 9 new passing integration tests for pub/sub and QoS flows

affects: [02-05-lwt-retained, 03-wildcard-trie, phase-03-auth]

tech-stack:
  added: []
  patterns:
    - "Inline delivery D-04: publisher actor calls subscriptionRegistry.getSubscriptions(topic) then writes directly to subscriber channels — no dispatch queue"
    - "Exact-match only D-06: wildcard subscriptions stored but getSubscriptions() returns empty set for non-matching topics"
    - "QoS 2 inbound dedup: inboundQos2 stores PublishMsg on PUBLISH, delivers on PUBREL, prevents double delivery on DUP retransmit"
    - "ByteBuf payload copy: ByteBufUtil.getBytes() called in MqttSessionHandler before finally{ReferenceCountUtil.safeRelease(msg)}"

key-files:
  created:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/Subscription.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/SubscriptionRegistry.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/DefaultSubscriptionRegistry.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/MqttPublishMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/MqttSubscribeMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/MqttUnsubscribeMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/MqttPubAckMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/MqttPubRecMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/MqttPubRelMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/MqttPubCompMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/DeliverMsg.java
  modified:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/MqttMessageGenerator.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/DefaultMqttMessageGenerator.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttChannelInitializer.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActorCreator.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttQosIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttSubscribeIntegrationTest.java

key-decisions:
  - "DefaultSubscriptionRegistry.subscribe() uses ConcurrentHashMap.compute() to atomically remove existing clientId subscription before adding new one — handles re-subscribe QoS update"
  - "inboundQos2 map stores PublishMsg (not Boolean marker) so message payload is available for delivery when PUBREL arrives"
  - "deliverToSubscribers() called immediately for QoS 0 and QoS 1 after PUBLISH; deferred until PUBREL for QoS 2"
  - "Wildcard subscriptions (+ and #) accepted and stored in registry but getSubscriptions() performs exact match only — D-06/D-07; Phase 3 will replace with trie lookup"

patterns-established:
  - "SubscriptionRegistry.getSubscriptions() is the single lookup point for delivery — Phase 3 swap from ConcurrentHashMap to trie only changes this method"
  - "Actor message types for QoS acks carry only packetId (int) — no extra payload needed for ack messages"

requirements-completed: [PROTO-02, PROTO-03]

duration: 8min
completed: 2026-04-06
---

# Phase 02 Plan 04: Subscribe/Unsubscribe and QoS 0/1/2 Delivery Summary

**In-memory exact-match subscription registry with inline QoS 0/1/2 delivery, full PUBACK/PUBREC/PUBREL/PUBCOMP handshakes, and QoS 2 deduplication via inboundQos2 map**

## Performance

- **Duration:** 8 min
- **Started:** 2026-04-06T13:07:26Z
- **Completed:** 2026-04-06T13:14:53Z
- **Tasks:** 2
- **Files modified:** 19

## Accomplishments

- Full MQTT pub/sub pipeline: SUBSCRIBE stores subscriptions, PUBLISH delivers to exact-match subscribers with QoS downgrade, UNSUBSCRIBE removes subscriptions and returns UNSUBACK
- QoS 0 fire-and-forget, QoS 1 with PUBACK, and QoS 2 with PUBREC/PUBREL/PUBCOMP handshakes all work end-to-end
- QoS 2 DUP retransmit prevention: inboundQos2 map stores PublishMsg on first PUBLISH and sends PUBREC without re-delivery on duplicate packetId
- Wildcard subscriptions accepted with valid SUBACK but no delivery (Phase 2 exact-match D-06/D-07)
- 9 new integration tests pass; total suite 51 tests, 0 failures

## Task Commits

1. **Task 1: Subscription registry, actor message types, message generator extensions** - `fd3c3af98` (feat)
2. **Task 2: Enable and implement SUBSCRIBE/UNSUBSCRIBE/QoS integration tests** - `34cd39523` (feat)

## Files Created/Modified

- `service/subscription/Subscription.java` - Per-client subscription with clientId, grantedQos, sessionCtx reference for inline delivery
- `service/subscription/SubscriptionRegistry.java` - Interface: subscribe, unsubscribe, getSubscriptions (exact), removeAllSubscriptions
- `service/subscription/DefaultSubscriptionRegistry.java` - ConcurrentHashMap<topic, Set<Subscription>> implementation
- `actors/client/msg/MqttPublishMsg.java` - Inbound PUBLISH with pre-copied byte[] payload
- `actors/client/msg/MqttSubscribeMsg.java` - Wraps Netty MqttSubscribeMessage
- `actors/client/msg/MqttUnsubscribeMsg.java` - Wraps Netty MqttUnsubscribeMessage
- `actors/client/msg/MqttPubAckMsg.java` / `MqttPubRecMsg.java` / `MqttPubRelMsg.java` / `MqttPubCompMsg.java` - QoS ack messages carrying packetId
- `actors/client/msg/DeliverMsg.java` - Outbound delivery message with publishMsg and deliveryQos
- `service/mqtt/MqttMessageGenerator.java` - Extended with 7 new methods for SUBACK, UNSUBACK, PUBLISH, PUBACK, PUBREC, PUBREL, PUBCOMP
- `service/mqtt/DefaultMqttMessageGenerator.java` - Implements all 7 new methods using Netty builders
- `server/MqttSessionHandler.java` - Routes PUBLISH (with ByteBufUtil.getBytes copy), SUBSCRIBE, UNSUBSCRIBE, PUBACK, PUBREC, PUBREL, PUBCOMP to actors
- `server/MqttChannelInitializer.java` - Injects SubscriptionRegistry into MqttSessionHandler
- `actors/client/ClientActor.java` - Handles 7 new message types, deliverToSubscribers with QoS downgrade, disconnect cleanup
- `actors/client/ClientActorCreator.java` - Passes SubscriptionRegistry to ClientActor
- `mqtt/MqttSubscribeIntegrationTest.java` - 5 tests: exact delivery, multi-topic SUBACK, unsubscribe, wildcard+, wildcard#
- `mqtt/MqttQosIntegrationTest.java` - 4 tests: QoS 0, QoS 1 PUBACK, QoS 2 full handshake, QoS downgrade

## Decisions Made

- `DefaultSubscriptionRegistry.subscribe()` uses `ConcurrentHashMap.compute()` to atomically handle re-subscribe QoS updates (remove existing + add new in one operation)
- `inboundQos2` stores `PublishMsg` object (not Boolean marker as originally scaffolded) so payload is available for delivery when PUBREL arrives
- Wildcard subscriptions stored but not matched in `getSubscriptions()` — Phase 3 swaps ConcurrentHashMap for trie without changing the interface

## Deviations from Plan

None — plan executed exactly as written, except one minor clarification: `inboundQos2` map type stays as `ConcurrentHashMap<Integer, Object>` (not changed to `ConcurrentHashMap<Integer, PublishMsg>`) to avoid modifying `ClientSessionCtx` mid-plan; `instanceof PublishMsg` pattern matching handles retrieval correctly.

## Issues Encountered

None — compilation succeeded on first attempt, all integration tests passed immediately.

## Known Stubs

- `processSubscribe()`: Retained message delivery on subscribe is commented `// STUBBED for Plan 05` — no data flows to subscriber on subscribe
- `processPublish()`: Retained message storage is commented `// Retained message storage (STUBBED for Plan 05)` — retain flag is processed but not stored

These stubs are intentional per plan scope; Plan 05 will implement retained message and LWT support.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- Full MQTT 3.1.1 pub/sub with QoS 0/1/2 is operational
- Plan 05 can add retained messages (Plan 05 will replace stubs in processSubscribe and processPublish) and LWT
- Phase 3 wildcard trie: only `DefaultSubscriptionRegistry.getSubscriptions()` needs replacement — interface stays the same
