---
phase: 06-mqtt-5-0
reviewed: 2026-04-11T14:32:00Z
depth: standard
files_reviewed: 27
files_reviewed_list:
  - lightweight/pom.xml
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActorCreator.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/DeliverMsg.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/MqttPublishMsg.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/common/BrokerConstants.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/Mqtt5Configuration.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandlerFactory.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/DefaultMqttMessageGenerator.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/MqttMessageGenerator.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/PublishMsg.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/DefaultRetainedMsgService.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/RetainedMsg.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/Subscription.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/ClientSessionCtx.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtx.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/util/MqttPropertiesUtil.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/util/MqttReasonCodeResolver.java
  - lightweight/src/main/resources/tbmq-lightweight.yml
  - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/Mqtt5PropertiesTest.java
  - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/Mqtt5ReasonCodeTest.java
  - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/Mqtt5SharedSubscriptionTest.java
  - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/Mqtt5TopicAliasTest.java
  - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/Mqtt5VersionNegotiationTest.java
  - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/security/CredentialServiceTest.java
findings:
  critical: 0
  warning: 5
  info: 3
  total: 8
status: issues_found
---

# Phase 06: Code Review Report

**Reviewed:** 2026-04-11T14:32:00Z
**Depth:** standard
**Files Reviewed:** 27
**Status:** issues_found

## Summary

Reviewed MQTT 5.0 support implementation across the TBMQ Lightweight module. The changes span session handler version detection, topic alias resolution (inbound/outbound), CONNACK/ACK reason codes, shared subscriptions, property forwarding, message expiry, subscription identifiers, and integration tests.

Overall quality is solid -- the code follows project conventions, Javadoc is thorough, and the MQTT 5.0 spec requirements are implemented correctly. The test suite covers the key protocol features well using Paho v5 and v3 clients.

Five warnings were identified: a race condition in outbound topic alias allocation, MQTT 5.0 SUBACK/UNSUBACK not using version-aware overloads, a missing topic alias validation when inbound aliases are disabled (inboundMax=0), and retained message lazy cleanup modifying a list during iteration. Three informational items were also noted.

## Warnings

### WR-01: Race condition in outbound topic alias allocation can exceed outboundMax

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtx.java:153-159`
**Issue:** The `getTopicAliasForPublish` method has a TOCTOU race between checking `serverMappings.size() < outboundMax` and `nextServerAlias.getAndIncrement()`. Two concurrent callers (from different dispatcher consumer threads via the actor system) could both pass the size check, both increment the counter, and one gets an alias > outboundMax. While the `if (alias <= outboundMax)` guard prevents using the invalid alias, the counter is permanently advanced, wasting alias slots. More importantly, `serverMappings.size()` check without synchronization means two threads can both see `size < outboundMax`, both call `getAndIncrement()`, and both put entries -- exceeding `outboundMax` by one or more entries in `serverMappings`.
**Fix:** Since `processDeliver` runs inside the actor's single-threaded mailbox, this race may not manifest if alias allocation is only called from actor context. However, the class itself uses `ConcurrentHashMap` and `AtomicInteger`, suggesting it was designed for concurrent access. Either (a) document that this method must only be called from the actor thread (removing the need for ConcurrentHashMap), or (b) use `computeIfAbsent` with a synchronized block:
```java
public int getTopicAliasForPublish(String topicName, int minTopicNameLength) {
    if (outboundMax == 0 || topicName.length() < minTopicNameLength) {
        return 0;
    }
    Integer existing = serverMappings.get(topicName);
    if (existing != null) {
        return existing;
    }
    // Use computeIfAbsent to atomically check and assign
    int[] allocated = {0};
    serverMappings.computeIfAbsent(topicName, k -> {
        int alias = nextServerAlias.getAndIncrement();
        if (alias <= outboundMax) {
            allocated[0] = alias;
            return alias;
        }
        return null; // returns null -> no mapping stored
    });
    return allocated[0];
}
```

### WR-02: SUBACK for MQTT 5.0 clients uses version-unaware overload (no properties)

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java:422-423`
**Issue:** The `processSubscribe` method always calls `createSubAck(packetId, grantedQosList)` (the MQTT 3.1.1 overload) regardless of MQTT version. For MQTT 5.0 clients, the SUBACK should use the overload that accepts `MqttProperties`. While the current SUBACK still works (Netty encodes it correctly for v5 connections), it means no SUBACK-level properties (e.g., Reason String, User Property) can be included, and the variable header will use `MqttMessageIdVariableHeader` instead of `MqttMessageIdAndPropertiesVariableHeader`. Some strict MQTT 5.0 clients may expect the properties header to be present even if empty.
**Fix:**
```java
if (sessionCtx.getMqttVersion() == MqttVersion.MQTT_5) {
    sessionCtx.getChannel().writeAndFlush(
            messageGenerator.createSubAck(packetId, grantedQosList, MqttProperties.NO_PROPERTIES));
} else {
    sessionCtx.getChannel().writeAndFlush(
            messageGenerator.createSubAck(packetId, grantedQosList));
}
```

### WR-03: UNSUBACK for MQTT 5.0 clients uses version-unaware overload (no per-topic reason codes)

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java:442-443`
**Issue:** The `processUnsubscribe` method always calls `createUnsubAck(packetId)` (the MQTT 3.1.1 overload). MQTT 5.0 UNSUBACK requires per-topic-filter reason codes in the payload (spec section 3.11.3). The current implementation sends UNSUBACK without any reason code payload for v5 clients, which is a protocol violation. MQTT 5.0 clients expect one reason code per topic filter in the UNSUBSCRIBE request.
**Fix:**
```java
if (sessionCtx.getMqttVersion() == MqttVersion.MQTT_5) {
    List<Short> reasonCodes = new ArrayList<>();
    for (String topic : topics) {
        // Success reason code for each unsubscribed topic
        reasonCodes.add((short) MqttReasonCodes.UnsubAck.SUCCESS.byteValue());
    }
    sessionCtx.getChannel().writeAndFlush(
            messageGenerator.createUnsubAck(packetId, reasonCodes, MqttProperties.NO_PROPERTIES));
} else {
    sessionCtx.getChannel().writeAndFlush(
            messageGenerator.createUnsubAck(packetId));
}
```

### WR-04: validateInboundAlias allows any alias when inboundMax is 0

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtx.java:175-182`
**Issue:** The `validateInboundAlias` method checks `if (inboundMax > 0 && topicAlias > inboundMax)`. When `inboundMax == 0` (topic aliases disabled), the condition `inboundMax > 0` is false, so the range check is skipped entirely. This means a client could send a topic alias to a broker that advertised TopicAliasMaximum=0 in CONNACK, and it would pass validation. Per MQTT 5.0 spec [MQTT-3.2.2-17]: if the Server sends TopicAliasMaximum=0 in CONNACK, the Client MUST NOT send Topic Aliases to the Server. Sending one is a protocol error.
**Fix:**
```java
public void validateInboundAlias(int topicAlias) {
    if (topicAlias == 0) {
        throw new RuntimeException("Topic Alias is zero - protocol error");
    }
    if (inboundMax == 0) {
        throw new RuntimeException("Topic aliases are disabled (TopicAliasMaximum=0) - protocol error");
    }
    if (topicAlias > inboundMax) {
        throw new RuntimeException("Topic Alias " + topicAlias + " exceeds inbound maximum " + inboundMax);
    }
}
```

### WR-05: Retained message lazy cleanup modifies list returned by trie during iteration

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/DefaultRetainedMsgService.java:64`
**Issue:** The `getRetainedMessages` method calls `results.removeIf(...)` on the list returned by `retainMsgTrie.get(topicFilter)`. If the trie's `get` method returns a view or unmodifiable list (rather than a mutable copy), `removeIf` will throw `UnsupportedOperationException`. The correctness depends on the trie implementation always returning a mutable `ArrayList`. This is fragile -- any future refactoring of the trie to return an unmodifiable view or Collections.unmodifiableList would break this code silently.
**Fix:** Wrap in a new `ArrayList` to guarantee mutability:
```java
List<RetainedMsg> results = new ArrayList<>(retainMsgTrie.get(topicFilter));
```

## Info

### IN-01: MQTT 5.0 PUBREL does not include reason code for MQTT 5.0 clients

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java:532`
**Issue:** In `processPubRec`, the outbound PUBREL is sent via `messageGenerator.createPubRel(packetId)` without a reason code. For MQTT 5.0 clients, it would be more correct to use `createPubRel(packetId, MqttReasonCodes.PubRel.SUCCESS)`. The MQTT 5.0 spec allows omitting the reason code when it is SUCCESS (byte value 0x00), so this is not a bug but an inconsistency with the approach used for PUBACK and PUBREC which do pass reason codes.
**Fix:** For consistency:
```java
sessionCtx.getChannel().writeAndFlush(
    messageGenerator.createPubRel(packetId, MqttReasonCodeResolver.pubRelSuccess(sessionCtx)));
```

### IN-02: PUBCOMP sent without reason code for MQTT 5.0 clients in processPubRel

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java:568`
**Issue:** Similar to IN-01, the PUBCOMP in the QoS 2 inbound flow is sent without a reason code for MQTT 5.0 clients. For consistency with the PUBACK/PUBREC handling pattern, it should use the version-aware overload.
**Fix:** Use `messageGenerator.createPubComp(packetId, MqttReasonCodeResolver.pubCompSuccess(sessionCtx))`.

### IN-03: Properties are double-copied during delivery for MQTT 5.0 messages

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java:597`
**Issue:** In `processDeliver`, `MqttPropertiesUtil.copyPublishPropertiesToDeliver` is called on `publishMsg.getProperties()`. However, the properties were already copied in `MqttSessionHandler.processPublish` (line 187) when the inbound PUBLISH was first received. This results in a second copy of the same properties during delivery to each subscriber. While not a correctness bug, it creates unnecessary object allocation. For a high-throughput broker this adds GC pressure proportional to (subscribers x properties-per-message).
**Fix:** Consider passing the already-copied properties through without a second copy, or documenting that the second copy is intentional (e.g., because each subscriber may get different additional properties like subscription identifier).

---

_Reviewed: 2026-04-11T14:32:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
