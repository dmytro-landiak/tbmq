---
phase: 06-mqtt-5-0
plan: 01
subsystem: mqtt5-foundation
tags: [mqtt5, utilities, domain-model, type-system, configuration]
dependency_graph:
  requires: []
  provides:
    - MqttPropertiesUtil (MQTT 5.0 property extraction/construction)
    - MqttReasonCodeResolver (version-aware reason code mapping)
    - TopicAliasCtx (bidirectional per-session topic alias context)
    - Mqtt5Configuration (YAML-backed MQTT 5.0 configuration bean)
    - BrokerConstants MQTT 5.0 property ID constants
    - Extended domain models: PublishMsg, RetainedMsg, DeliverMsg, MqttPublishMsg, Subscription, ClientSessionCtx
    - Extended MqttMessageGenerator interface with MQTT 5.0 overloads
  affects:
    - ClientActor (Subscription constructor updated)
    - MqttSessionHandler (MqttPublishMsg constructor updated)
    - DefaultMsgDispatcherService (DeliverMsg constructor updated)
tech_stack:
  added:
    - Eclipse Paho MQTT v5 client 1.2.5 (test dependency)
  patterns:
    - Netty MqttPubReplyMessageVariableHeader for MQTT 5.0 ACK reason codes
    - MqttReasonCodeAndPropertiesVariableHeader for DISCONNECT
    - @Builder.Default for safe NO_PROPERTIES defaults
key_files:
  created:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/util/MqttPropertiesUtil.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/util/MqttReasonCodeResolver.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtx.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/Mqtt5Configuration.java
  modified:
    - lightweight/pom.xml
    - lightweight/src/main/resources/tbmq-lightweight.yml
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/common/BrokerConstants.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/PublishMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/RetainedMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/DeliverMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/MqttPublishMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/Subscription.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/ClientSessionCtx.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/MqttMessageGenerator.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/DefaultMqttMessageGenerator.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java
decisions:
  - "Pre-extended ClientSessionCtx and RetainedMsg in Task 1 due to forward compilation dependency from MqttReasonCodeResolver/MqttPropertiesUtil"
  - "TopicAliasCtx uses RuntimeException (not custom exception) for invalid alias — caller catches and disconnects client"
  - "MqttPropertiesUtil.isRetainedMsgExpired overload uses RetainedMsg directly rather than taking long createdTime separately"
  - "Subscription constructor call sites updated with shareName=null, subscriptionId=0 (MQTT 3.1.1 defaults) to maintain existing behavior"
metrics:
  duration: "25 minutes"
  completed_date: "2026-04-11"
  tasks: 2
  files: 15
---

# Phase 6 Plan 01: MQTT 5.0 Foundation Infrastructure Summary

**One-liner:** MQTT 5.0 type system and utility layer established — property extraction utilities, version-aware reason codes, topic alias context, extended domain models, and MqttMessageGenerator MQTT 5.0 overloads — all compilable and ready for wiring in Plans 02 and 03.

## What Was Built

### Task 1: MQTT 5.0 Utility Classes, Constants, Config, and Dependency

**BrokerConstants** extended with 12 MQTT 5.0 property ID constants (PAYLOAD_FORMAT_INDICATOR_PROP_ID through USER_PROPERTY_PROP_ID), DEFAULT_RECEIVE_MAXIMUM, SHARED_SUBSCRIPTION_PREFIX, and SHARE_NAME_IDX.

**MqttPropertiesUtil** (new) — static utility for all MQTT 5.0 property operations:
- Extraction: session expiry, topic alias max, receive max, topic alias, subscription ID, pub expiry interval, user properties
- Construction: all add*ToProps methods matching the above
- Expiry helpers: `isRetainedMsgExpired()`, `getRemainingExpiryInterval()`
- Delivery copy: `copyPublishPropertiesToDeliver()` for outbound PUBLISH construction
- Null-safe: all methods handle `props == null` and `props == MqttProperties.NO_PROPERTIES`

**MqttReasonCodeResolver** (new) — version-aware static resolver:
- CONNACK codes: connectionRefusedNotAuthorized, connectionRefusedUnspecified, connectionRefusedBadCredentials
- PUBACK/PUBREC codes: pubAckSuccess, pubAckNotAuthorized, pubRecSuccess, pubRecNotAuthorized
- SUBACK codes: subAckNotAuthorized
- DISCONNECT codes: disconnectProtocolError, disconnectTopicAliasInvalid (version-independent, MQTT 5.0 only)
- Returns null for 3.1.1 ACK packets (no reason code byte in wire format)

**TopicAliasCtx** (new) — bidirectional topic alias context:
- `DISABLED_TOPIC_ALIASES` static singleton for 3.1.1 clients
- `clientMappings` (ConcurrentHashMap) for inbound alias resolution
- `serverMappings` (ConcurrentHashMap) + `nextServerAlias` (AtomicInteger) for outbound alias allocation
- `getTopicNameByAlias(topicName, alias)` — stores/resolves inbound aliases with validation
- `getTopicAliasForPublish(topicName, minLength)` — lazy outbound alias allocation up to maxTopicAlias
- `validateTopicAlias(alias)` — throws RuntimeException for alias=0 or alias>max (T-06-01 mitigation)

**Mqtt5Configuration** (new) — Spring @Configuration bean:
- `topicAliasMax` (default: 10, env: TBMQ_TOPIC_ALIAS_MAX)
- `receiveMaximum` (default: 65535, env: TBMQ_RECEIVE_MAXIMUM)
- `minTopicAliasLength` (default: 5, env: TBMQ_MIN_TOPIC_ALIAS_LENGTH)

**tbmq-lightweight.yml** — added `tbmq.mqtt5.*` section with all three properties.

**pom.xml** — added Eclipse Paho MQTT v5 client 1.2.5 as test dependency.

### Task 2: Domain Model Extensions and MqttMessageGenerator MQTT 5.0 Overloads

**PublishMsg** — added `MqttProperties properties` with `@Builder.Default = NO_PROPERTIES`

**RetainedMsg** — added `long createdTime` (@Builder.Default = System.currentTimeMillis()) and `MqttProperties properties` (@Builder.Default = NO_PROPERTIES)

**DeliverMsg** — added `int subscriptionId` field (3 constructor params total via @RequiredArgsConstructor)

**MqttPublishMsg** — added `MqttProperties properties` field (7 constructor params total)

**Subscription** — added `String shareName` (null for non-shared) and `int subscriptionId` (5 constructor params total)

**ClientSessionCtx** — added:
- `volatile MqttVersion mqttVersion` (default: MQTT_3_1_1)
- `volatile TopicAliasCtx topicAliasCtx` (default: DISABLED_TOPIC_ALIASES)
- `volatile int receiveMaximum` (default: DEFAULT_RECEIVE_MAXIMUM)

**MqttMessageGenerator** — extended interface with 9 MQTT 5.0 overloads: createConnAck(3 params), createPublish(7 params), createPubAck/PubRec/PubRel/PubComp with reasonCode, createSubAck with properties, createUnsubAck with reasonCodes+properties, createDisconnect.

**DefaultMqttMessageGenerator** — implemented all 9 new overloads using Netty's MqttPubReplyMessageVariableHeader, MqttReasonCodeAndPropertiesVariableHeader, and MqttMessageBuilders patterns.

**Constructor call site fixes** — updated to pass new required params with MQTT 3.1.1 defaults: ClientActor (Subscription), MqttSessionHandler (MqttPublishMsg), DefaultMsgDispatcherService (DeliverMsg).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Forward compilation dependency: ClientSessionCtx.mqttVersion needed by MqttReasonCodeResolver**
- **Found during:** Task 1 compile
- **Issue:** MqttReasonCodeResolver.java calls `ctx.getMqttVersion()` but ClientSessionCtx had no `mqttVersion` field yet (planned for Task 2)
- **Fix:** Added `mqttVersion`, `topicAliasCtx`, `receiveMaximum` fields to ClientSessionCtx as part of Task 1 (pre-applied Task 2's changes to ClientSessionCtx)
- **Files modified:** ClientSessionCtx.java
- **Commit:** 581961199

**2. [Rule 3 - Blocking] Forward compilation dependency: RetainedMsg.getCreatedTime/getProperties needed by MqttPropertiesUtil**
- **Found during:** Task 1 compile
- **Issue:** MqttPropertiesUtil.isRetainedMsgExpired(RetainedMsg) calls `getCreatedTime()` and `getProperties()` which were not yet on RetainedMsg (planned for Task 2)
- **Fix:** Added `createdTime` and `properties` fields to RetainedMsg as part of Task 1
- **Files modified:** RetainedMsg.java
- **Commit:** 581961199

**3. [Rule 3 - Blocking] Constructor arity mismatch in existing callers after domain model extension**
- **Found during:** Task 2 compile
- **Issue:** Extending Subscription (5 params), MqttPublishMsg (7 params), DeliverMsg (3 params) broke existing callers using old arities
- **Fix:** Updated call sites: ClientActor (Subscription with shareName=null, subscriptionId=0), MqttSessionHandler (MqttPublishMsg with NO_PROPERTIES), DefaultMsgDispatcherService (DeliverMsg with subscriptionId=0)
- **Files modified:** ClientActor.java, MqttSessionHandler.java, DefaultMsgDispatcherService.java
- **Commit:** cac848d1a

## Threat Mitigations Applied

| Threat | Mitigation |
|--------|-----------|
| T-06-01 (DoS via invalid topic alias) | TopicAliasCtx.validateTopicAlias() throws RuntimeException for alias=0 or alias>max |
| T-06-02 (NPE from null MqttProperties) | MqttPropertiesUtil guards all methods with null/NO_PROPERTIES checks |

## Known Stubs

None — this plan creates purely foundational infrastructure (no data flows to UI rendering or protocol handlers yet). All fields have safe defaults for MQTT 3.1.1 backward compatibility. Wiring of the MQTT 5.0 infrastructure into protocol handlers occurs in Plans 02 and 03.

## Self-Check

**Created files exist:**
- lightweight/src/main/java/.../util/MqttPropertiesUtil.java — FOUND
- lightweight/src/main/java/.../util/MqttReasonCodeResolver.java — FOUND
- lightweight/src/main/java/.../session/TopicAliasCtx.java — FOUND
- lightweight/src/main/java/.../config/Mqtt5Configuration.java — FOUND

**Commits exist:**
- 581961199 — FOUND
- cac848d1a — FOUND

**Build:** `mvn compile` — BUILD SUCCESS (main sources and test sources)

## Self-Check: PASSED
