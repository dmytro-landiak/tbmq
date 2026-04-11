---
phase: 06-mqtt-5-0
fixed_at: 2026-04-11T14:45:00Z
review_path: .planning/phases/06-mqtt-5-0/06-REVIEW.md
iteration: 1
findings_in_scope: 5
fixed: 5
skipped: 0
status: all_fixed
---

# Phase 06: Code Review Fix Report

**Fixed at:** 2026-04-11T14:45:00Z
**Source review:** .planning/phases/06-mqtt-5-0/06-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 5
- Fixed: 5
- Skipped: 0

## Fixed Issues

### WR-01: Race condition in outbound topic alias allocation can exceed outboundMax

**Files modified:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtx.java`
**Commit:** f68edb238
**Applied fix:** Replaced the TOCTOU-vulnerable `size() < outboundMax` check followed by `getAndIncrement()` + `put()` with an atomic `computeIfAbsent` that performs alias allocation inside the lambda. This ensures that concurrent callers cannot both pass the capacity check and exceed `outboundMax` entries in `serverMappings`.

### WR-02: SUBACK for MQTT 5.0 clients uses version-unaware overload (no properties)

**Files modified:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java`
**Commit:** 8aebb7295
**Applied fix:** Added version check in `processSubscribe` -- MQTT 5.0 clients now receive SUBACK via the `createSubAck(packetId, grantedQosList, MqttProperties.NO_PROPERTIES)` overload which produces a `MqttMessageIdAndPropertiesVariableHeader`, while MQTT 3.1.1 clients continue to use the original overload.

### WR-03: UNSUBACK for MQTT 5.0 clients uses version-unaware overload (no per-topic reason codes)

**Files modified:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java`
**Commit:** 0801b615d
**Applied fix:** Added version check in `processUnsubscribe` -- MQTT 5.0 clients now receive UNSUBACK with per-topic-filter SUCCESS reason codes via `createUnsubAck(packetId, reasonCodes, MqttProperties.NO_PROPERTIES)`, satisfying the MQTT 5.0 spec requirement (section 3.11.3). MQTT 3.1.1 clients continue to use the original overload.

### WR-04: validateInboundAlias allows any alias when inboundMax is 0

**Files modified:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtx.java`
**Commit:** c00ec400c
**Applied fix:** Added explicit check for `inboundMax == 0` before the range check. When the broker advertises TopicAliasMaximum=0 in CONNACK, any inbound topic alias is now correctly rejected as a protocol error per [MQTT-3.2.2-17]. The previous `inboundMax > 0 && topicAlias > inboundMax` condition was split into two separate checks: one for disabled aliases and one for range violation.

### WR-05: Retained message lazy cleanup modifies list returned by trie during iteration

**Files modified:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/DefaultRetainedMsgService.java`
**Commit:** 32aec2517
**Applied fix:** Wrapped the trie result in `new ArrayList<>(...)` to guarantee a mutable, independent copy before calling `removeIf`. Added the missing `java.util.ArrayList` import. This prevents `UnsupportedOperationException` if the trie implementation is ever refactored to return an unmodifiable or view-backed list.

---

_Fixed: 2026-04-11T14:45:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
