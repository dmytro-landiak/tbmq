---
phase: 06-mqtt-5-0
plan: 03
subsystem: testing
tags: [mqtt5, paho-v5, integration-tests, shared-subscriptions, topic-alias, reason-codes]

# Dependency graph
requires:
  - phase: 06-01
    provides: "MQTT 5.0 foundation infrastructure (Subscription, DeliverMsg, TopicAliasCtx, MqttPropertiesUtil)"
  - phase: 06-02
    provides: "MQTT 5.0 handler-actor-dispatcher chain (version detection, CONNACK properties, shared sub dispatch)"
provides:
  - "End-to-end integration tests for all MQTT 5.0 requirements (PROTO-08, PROTO-09, PROTO-10)"
  - "Validation of all locked decisions D-01 through D-14 (except D-07 deferred)"
  - "AbstractMqtt5IntegrationTest base class for Paho v5 client tests"
affects: [future-mqtt5-features, performance-testing]

# Tech tracking
tech-stack:
  added: [org.eclipse.paho.mqttv5.client, org.eclipse.paho.mqttv5.common]
  patterns: [paho-v5-connectWithResult, setCallback-subscribe-pattern, makeCallback-helper]

key-files:
  created:
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/Mqtt5VersionNegotiationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/Mqtt5PropertiesTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/Mqtt5TopicAliasTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/Mqtt5ReasonCodeTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/Mqtt5SharedSubscriptionTest.java
  modified:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/DefaultMqttMessageGenerator.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtx.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/util/MqttPropertiesUtil.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/security/CredentialServiceTest.java

key-decisions:
  - "Use connectWithResult() instead of connect() for Paho v5 to access CONNACK response properties"
  - "Use setCallback() + subscribe(topic, qos) instead of subscribe(topic, qos, listener) to avoid Paho v5 1.2.5 recursive subscribe bug"
  - "Use setCallback() for MQTT 3.1.1 shared subscription tests because Paho v3 routes inline listeners by topic filter, not PUBLISH topic name"
  - "Omit ReceiveMaximum from CONNACK when value is default 65535 to avoid Paho v5 timing issue"
  - "Separate inbound/outbound TopicAliasCtx capacities per MQTT 5.0 spec (broker CONNACK vs client CONNECT limits)"
  - "Build SUBACK directly with MqttSubAckPayload(int[]) to support reason codes > 2 (NOT_AUTHORIZED = 0x87)"

patterns-established:
  - "Paho v5 test pattern: createV5Client() + connectWithResult() + setCallback() for message reception"
  - "makeCallback(AtomicInteger) helper for shared subscription tests to reduce callback boilerplate"
  - "MqttAsyncClient for subscription identifier tests — only async API exposes subscribe(MqttSubscription[], ..., MqttProperties)"

requirements-completed: [PROTO-08, PROTO-09, PROTO-10]

# Metrics
duration: 7min
completed: 2026-04-11
---

# Phase 06 Plan 03: MQTT 5.0 Integration Tests Summary

**28 passing integration tests validating MQTT 5.0 version negotiation, properties forwarding, topic aliases, reason codes, and shared subscriptions with Paho v5 clients**

## Performance

- **Duration:** 7 min
- **Started:** 2026-04-11T11:10:12Z
- **Completed:** 2026-04-11T11:16:53Z
- **Tasks:** 2/2
- **Files modified:** 11

## Accomplishments
- All 5 MQTT 5.0 test classes passing (28 new test methods) covering PROTO-08, PROTO-09, PROTO-10 requirements
- Fixed critical MQTT 5.0 infrastructure issues discovered during test execution: SUBACK reason code construction, ReceiveMaximum CONNACK handling, TopicAliasCtx inbound/outbound separation, message expiry interval forwarding
- Full test suite green: 146 tests run, 0 failures, 0 errors (3 pre-existing skipped)
- Cross-version coexistence validated: MQTT 3.1.1 and 5.0 clients on same port with message delivery between versions

## Task Commits

Each task was committed atomically:

1. **Task 1: Fix MQTT 5.0 infrastructure for test support** - `ba354772f` (fix)
2. **Task 2: Add all 5 MQTT 5.0 integration test classes** - `4eafad853` (feat)

## Files Created/Modified

### Created
- `lightweight/src/test/java/.../mqtt/Mqtt5VersionNegotiationTest.java` - PROTO-08: v5 connect, v3/v5 coexistence, CONNACK properties, session expiry override, auth failure (7 tests)
- `lightweight/src/test/java/.../mqtt/Mqtt5PropertiesTest.java` - PROTO-09: user properties, payload format, content type, response topic, message expiry, subscription identifier (6 tests)
- `lightweight/src/test/java/.../mqtt/Mqtt5TopicAliasTest.java` - PROTO-09: repeated publish, topic name preservation, server-side alias transparency (3 tests)
- `lightweight/src/test/java/.../mqtt/Mqtt5ReasonCodeTest.java` - PROTO-08/09: QoS 1/2 reason codes, SUBACK ACL denial, CONNACK auth failure, multi-QoS (6 tests)
- `lightweight/src/test/java/.../mqtt/Mqtt5SharedSubscriptionTest.java` - PROTO-10: round-robin delivery, v3.1.1 shared subs, shared+non-shared coexistence, no retained on shared (5 tests + 1 extra round-robin)

### Modified
- `lightweight/src/main/java/.../actors/client/ClientActor.java` - Omit ReceiveMaximum from CONNACK when default
- `lightweight/src/main/java/.../server/MqttSessionHandler.java` - Parse client's CONNECT TopicAliasMaximum for outbound aliases
- `lightweight/src/main/java/.../service/mqtt/DefaultMqttMessageGenerator.java` - Build SUBACK with raw reason codes instead of MqttQoS.valueOf()
- `lightweight/src/main/java/.../session/TopicAliasCtx.java` - Separate inbound/outbound alias capacities
- `lightweight/src/main/java/.../util/MqttPropertiesUtil.java` - Copy message expiry interval in publish properties
- `lightweight/src/test/java/.../security/CredentialServiceTest.java` - Add missing port properties to prevent test context conflicts

## Decisions Made

1. **connectWithResult() over connect()**: Paho v5 `connect()` returns void; `connectWithResult()` returns `IMqttToken` carrying CONNACK response properties needed for testing D-02, D-13.
2. **setCallback() over inline subscribe listener**: Paho v5 1.2.5 has a recursive subscribe bug with `subscribe(String, int, IMqttMessageListener)`. Using `setCallback()` + `subscribe(String, int)` avoids StackOverflow.
3. **setCallback() for v3 shared subscription test**: Paho v3 routes inline listeners by matching PUBLISH topic name against subscribed topic filter. Since shared subscriptions publish to the actual topic (not the `$share/` filter), the inline listener never fires. Using global callback fixes this.
4. **Omit ReceiveMaximum=65535 from CONNACK**: Per MQTT 5.0 spec section 3.2.2.3.3, absence means 65535. Explicitly sending it triggers a Paho 1.2.5 timing issue where the value isn't applied before the first publish.
5. **Direct SUBACK construction**: `MqttMessageBuilders.SubAckBuilder` calls `MqttQoS.valueOf()` which throws for values > 2. MQTT 5.0 SUBACK reason codes include NOT_AUTHORIZED (0x87), requiring direct `MqttSubAckPayload(int[])` construction.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed SUBACK construction for MQTT 5.0 reason codes**
- **Found during:** Task 1 (infrastructure fixes)
- **Issue:** `DefaultMqttMessageGenerator.createSubAck()` used `MqttQoS.valueOf()` which throws IllegalArgumentException for reason codes > 2 (e.g., NOT_AUTHORIZED = 0x87)
- **Fix:** Replaced SubAckBuilder with direct MqttSubAckMessage construction using raw int[] payload
- **Files modified:** DefaultMqttMessageGenerator.java
- **Verification:** testSubackReasonCodeForAclDenied passes
- **Committed in:** ba354772f (Task 1 commit)

**2. [Rule 1 - Bug] Fixed ReceiveMaximum CONNACK causing Paho v5 timing issue**
- **Found during:** Task 1 (infrastructure fixes)
- **Issue:** Explicitly sending ReceiveMaximum=65535 in CONNACK caused Paho v5 1.2.5 to apply it asynchronously, leading to "Too many publishes in progress" errors when using void connect() API
- **Fix:** Omit ReceiveMaximum from CONNACK when it equals the MQTT 5.0 default (65535)
- **Files modified:** ClientActor.java
- **Verification:** All v5 tests pass without "Too many publishes" errors
- **Committed in:** ba354772f (Task 1 commit)

**3. [Rule 1 - Bug] Fixed TopicAliasCtx inbound/outbound capacity separation**
- **Found during:** Task 1 (infrastructure fixes)
- **Issue:** TopicAliasCtx used a single `maxTopicAlias` for both client-to-broker and broker-to-client aliases. MQTT 5.0 spec requires separate limits from CONNACK and CONNECT.
- **Fix:** Replaced `(boolean enabled, int maxTopicAlias)` constructor with `(int inboundMax, int outboundMax)`. Updated MqttSessionHandler to parse client's CONNECT TopicAliasMaximum.
- **Files modified:** TopicAliasCtx.java, MqttSessionHandler.java
- **Verification:** Topic alias tests pass with correct bidirectional handling
- **Committed in:** ba354772f (Task 1 commit)

**4. [Rule 1 - Bug] Fixed message expiry interval not copied for retained message expiry checks**
- **Found during:** Task 1 (infrastructure fixes)
- **Issue:** `MqttPropertiesUtil.copyPublishPropertiesToDeliver()` did not copy the message expiry interval property, so retained messages could not be checked for expiry
- **Fix:** Added message expiry interval copying in copyPublishPropertiesToDeliver()
- **Files modified:** MqttPropertiesUtil.java
- **Verification:** testMessageExpiryIntervalOnRetainedMessage passes
- **Committed in:** ba354772f (Task 1 commit)

**5. [Rule 3 - Blocking] Fixed CredentialServiceTest port conflict in full test suite**
- **Found during:** Task 2 (running full test suite)
- **Issue:** CredentialServiceTest was missing `tbmq.netty.port=0` and `management.server.port=0`, causing port binding conflicts when running in the full suite
- **Fix:** Added the missing properties to @TestPropertySource
- **Files modified:** CredentialServiceTest.java
- **Verification:** Full test suite passes (146 tests, 0 errors)
- **Committed in:** ba354772f (Task 1 commit)

**6. [Rule 1 - Bug] Fixed v3 shared subscription test using inline listener**
- **Found during:** Task 2 (testSharedSubscriptionWithMqtt311Client timeout)
- **Issue:** Paho v3 inline subscribe listener routes by topic filter match, but broker delivers to actual topic (without `$share/` prefix). The inline listener for `$share/group/topic` never matches the PUBLISH topic `topic`.
- **Fix:** Changed to setCallback() + subscribe() pattern which receives all incoming messages regardless of topic match
- **Files modified:** Mqtt5SharedSubscriptionTest.java
- **Verification:** testSharedSubscriptionWithMqtt311Client passes within 5 seconds
- **Committed in:** 4eafad853 (Task 2 commit)

---

**Total deviations:** 6 auto-fixed (5 Rule 1 bugs, 1 Rule 3 blocking)
**Impact on plan:** All auto-fixes necessary for correctness. Bugs 1-4 were in the MQTT 5.0 infrastructure from Plan 06-02 that were only discoverable through integration testing. Bug 5 was a pre-existing test infrastructure issue. Bug 6 was a Paho v3 client behavior mismatch. No scope creep.

## Issues Encountered
- Paho v5 1.2.5 API quirks required workarounds: void connect() doesn't expose CONNACK properties (use connectWithResult()), recursive subscribe bug (use setCallback()), ReceiveMaximum timing issue (omit default value)
- These are documented in key-decisions and patterns-established for future reference

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- All MQTT 5.0 requirements (PROTO-08, PROTO-09, PROTO-10) are validated end-to-end with passing integration tests
- All locked decisions (D-01 through D-06, D-08 through D-14) are exercised by at least one test
- Phase 06 MQTT 5.0 support is feature-complete and test-verified
- Full test suite is green (146 tests, 0 failures, 0 errors)

## Self-Check: PASSED

- All 7 key files verified present on disk
- Commit ba354772f (Task 1) verified in git log
- Commit 4eafad853 (Task 2) verified in git log
- Commit 54c99162a (Task 1 base class, from prior wave) verified in git log

---
*Phase: 06-mqtt-5-0*
*Completed: 2026-04-11*
