# Phase 6: MQTT 5.0 - Research

**Researched:** 2026-04-11
**Domain:** MQTT 5.0 protocol, Netty MQTT codec, shared subscriptions, topic aliases
**Confidence:** HIGH — all findings verified against the live codebase and upstream TBMQ source

## Summary

Phase 6 adds MQTT 5.0 protocol support to the lightweight broker. The upstream TBMQ codebase already implements the full MQTT 5.0 surface; this phase is primarily a **port-and-trim** exercise — copy the upstream classes that are relevant to R1 scope, strip out Kafka/protobuf/cluster dependencies, and wire them into the lightweight actor system.

Netty 4.1.x (already pinned in the project) ships with complete MQTT 5.0 codec support: `MqttVersion`, `MqttProperties`, `MqttReasonCodes`, and `MqttReasonCodeAndPropertiesVariableHeader`. No codec changes or new Netty versions are needed.

The implementation involves seven interacting change areas: (1) version detection in `MqttSessionHandler`, (2) session context enrichment in `ClientSessionCtx`, (3) property extraction utilities in a new `MqttPropertiesUtil`, (4) version-aware reason-code mapping in a new `MqttReasonCodeResolver`, (5) message generator API extension for properties, (6) shared subscription parsing and round-robin dispatch in `ClientActor` and `DefaultMsgDispatcherService`, and (7) topic alias bidirectional resolution via a ported `TopicAliasCtx`. A Paho MQTT v5 test client dependency must be added to the lightweight pom for integration tests.

**Primary recommendation:** Port upstream utility classes (`MqttPropertiesUtil`, `TopicAliasCtx`, `MqttReasonCodeResolver`) with minimal changes, then thread MQTT version awareness through the existing handler/actor/generator chain. Do not redesign the actor model — version-aware branching belongs inside existing message handlers.

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Session Expiry**
- D-01: Accept Session Expiry Interval from CONNECT but always treat as 0 — session ends immediately on disconnect. The broker is allowed to override session expiry per spec. R1 has no persistent sessions.
- D-02: Include Session Expiry Interval = 0 in CONNACK properties only when the client requested a non-zero value — signals the override without adding bytes to every CONNACK.
- D-03: Ignore Session Expiry Interval in DISCONNECT packets — always clean up immediately, consistent with D-01.

**Properties Coverage**
- D-04: Fully support: User Properties (forwarded through message pipeline), Message Expiry Interval (TTL for retained messages), Receive Maximum (per-session in-flight QoS 1/2 limit), Payload Format Indicator, and Content Type.
- D-05: Pass-through without interpretation: Response Topic and Correlation Data on PUBLISH — enables client-to-client request-response patterns.
- D-06: Support Subscription Identifiers — client assigns integer IDs to subscriptions, broker includes matching IDs in delivered PUBLISH messages.
- D-07: Defer Enhanced Authentication (SCRAM/challenge-response via AUTH packets) to R2. R1 has username/password and X.509 mTLS from Phase 4.
- D-08: Full MQTT 5.0 reason codes in all packet types — CONNACK, PUBACK, PUBREC, PUBREL, PUBCOMP, SUBACK, UNSUBACK, DISCONNECT. No shortcuts.

**Shared Subscriptions**
- D-09: Round-robin distribution strategy for `$share/group/topic` — AtomicInteger counter per group, rotate through active members sequentially.
- D-10: Shared subscriptions stored in the existing subscription trie — strip `$share/group/` prefix, store the actual topic filter with a group name annotation on the Subscription object. Trie matching works unchanged; shared vs. non-shared resolved at delivery time by grouping matches by share name and picking one per group.
- D-11: Shared subscriptions available to both MQTT 5.0 and MQTT 3.1.1 clients — matches upstream TBMQ behavior.

**Topic Aliases**
- D-12: Bidirectional topic alias support — client sends aliases to broker AND broker allocates aliases for outbound PUBLISH to client. Port upstream's `TopicAliasCtx` pattern with ConcurrentHashMap for both directions.
- D-13: Default Topic Alias Maximum = 10 per connection. Configurable via env var (`TBMQ_TOPIC_ALIAS_MAX`). Matches upstream TBMQ default.
- D-14: Server-side alias allocation uses a minimum topic name length threshold — only alias topics longer than N characters. Configurable via env var. Avoids wasting alias slots on short topics.

### Claude's Discretion
- MqttPropertiesUtil implementation details (which upstream methods to port vs. write fresh)
- Reason code resolver class design and mapping strategy
- TopicAliasCtx minimum topic length threshold default value
- Receive Maximum default value and enforcement mechanism details
- Message Expiry Interval tracking approach for retained messages (timestamp storage, expiry check timing)
- Subscription Identifier storage on the Subscription object and propagation to delivered messages
- Shared subscription group cleanup on last member unsubscribe
- MQTT 5.0 DISCONNECT packet handling (broker-initiated disconnect with reason code)
- Version-aware branching pattern in ClientActor and MqttMessageGenerator

### Deferred Ideas (OUT OF SCOPE)
- Enhanced Authentication (SCRAM/AUTH packets) — Multi-round challenge-response auth. Significant complexity. Deferred to R2.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PROTO-08 | Broker accepts MQTT 5.0 CONNECT packets and negotiates protocol version with 3.1.1 clients | Version detection via `connectMsg.variableHeader().version()` returning int level 5. Netty codec already decodes MQTT 5.0 CONNECT with properties. |
| PROTO-09 | Broker handles MQTT 5.0 properties: session expiry interval, user properties, reason codes, and topic aliases | Upstream `MqttPropertiesUtil` and `TopicAliasCtx` provide all property extraction/construction. Reason codes via `MqttReasonCodeResolver` pattern verified in upstream. |
| PROTO-10 | Broker supports MQTT 5.0 shared subscriptions ($share/group/topic) for load-balanced message delivery | Shared subscription parsing helpers in `NettyMqttConverter` (upstream). Round-robin delivery via `AtomicInteger` counter per group. Trie changes not needed. |
</phase_requirements>

---

## Standard Stack

### Core (already in lightweight pom)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `io.netty:netty-codec-mqtt` | 4.1.130 (via Spring Boot BOM) | MQTT 5.0 protocol codec — `MqttVersion`, `MqttProperties`, `MqttReasonCodes` | Already pinned; has full MQTT 5.0 support since Netty 4.1.68 |
| `org.eclipse.paho.client.mqttv3` | 1.2.5 | Integration test client for MQTT 3.1.1 verification | Already in test scope |

### New Dependency Required

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.eclipse.paho.mqttv5.client` | 1.2.5 | Integration test client for MQTT 5.0 verification | Test scope only — already in root POM dependency management; must be added to `lightweight/pom.xml` |

**Version verification:** [VERIFIED: pom.xml line 978] The root POM already manages `org.eclipse.paho.mqttv5.client` at version 1.2.5 in `dependencyManagement`. The lightweight pom currently only declares the v3 client. The v5 client must be added in test scope to `lightweight/pom.xml`.

**Installation addition to lightweight/pom.xml:**
```xml
<!-- Eclipse Paho MQTT v5 client — integration tests for MQTT 5.0 support -->
<dependency>
    <groupId>org.eclipse.paho</groupId>
    <artifactId>org.eclipse.paho.mqttv5.client</artifactId>
    <scope>test</scope>
</dependency>
```

No version needed — managed by root POM at 1.2.5.

---

## Architecture Patterns

### Recommended Project Structure

New classes in the lightweight module:

```
lightweight/src/main/java/.../lightweight/
├── util/
│   ├── MqttPropertiesUtil.java        # MQTT 5.0 property extraction/construction (port upstream)
│   └── MqttReasonCodeResolver.java    # Version-aware reason code mapping (port upstream)
├── session/
│   └── TopicAliasCtx.java             # Bidirectional topic alias context (port upstream)
├── service/
│   └── subscription/
│       └── shared/
│           └── SharedSubscriptionCounter.java  # ConcurrentHashMap<String,AtomicInteger> for round-robin
```

Modified classes:
```
lightweight/src/main/java/.../lightweight/
├── server/
│   └── MqttSessionHandler.java         # Add version detection, topic alias resolution
├── session/
│   └── ClientSessionCtx.java           # Add mqttVersion, topicAliasCtx, receiveMaximum
├── actors/client/
│   └── ClientActor.java                # Add version-aware branching, shared sub delivery
├── actors/client/msg/
│   └── MqttPublishMsg.java             # Add MqttProperties field for forwarding
├── service/mqtt/
│   ├── MqttMessageGenerator.java       # Add MqttProperties overloads for 5.0 packets
│   ├── DefaultMqttMessageGenerator.java# Implement new overloads
│   └── PublishMsg.java                 # Add MqttProperties field
├── service/mqtt/retain/
│   └── RetainedMsg.java               # Add createdTime + MqttProperties for expiry tracking
├── service/subscription/
│   └── Subscription.java              # Add shareName (nullable) and subscriptionId fields
└── service/dispatch/
    └── DefaultMsgDispatcherService.java # Add shared subscription grouping in deliverToSubscribers
```

### Pattern 1: MQTT Version Detection (PROTO-08)

**What:** Extract MQTT protocol version from CONNECT packet, store on session context, use for all version-aware branching.

**When to use:** In `MqttSessionHandler.processConnect()` before creating the actor.

```java
// Source: upstream MqttSessionHandler line 308-312 [VERIFIED]
// connectMsg.variableHeader().version() returns the raw protocol level
// MqttVersion.MQTT_3_1_1 uses level 4; MqttVersion.MQTT_5 uses level 5
int versionByte = connectMsg.variableHeader().version();
MqttVersion mqttVersion;
if (versionByte == MqttVersion.MQTT_5.protocolLevel()) {
    mqttVersion = MqttVersion.MQTT_5;
} else {
    mqttVersion = MqttVersion.MQTT_3_1_1; // default — treat unknown as 3.1.1
}
sessionCtx.setMqttVersion(mqttVersion);
```

**Key insight:** Version is stored on `ClientSessionCtx`, not passed message-by-message. All downstream handlers check `sessionCtx.getMqttVersion()`.

### Pattern 2: MqttPropertiesUtil (PROTO-09)

**What:** Utility class for extracting and adding MQTT 5.0 properties from/to Netty `MqttProperties` objects.

**When to use:** In `ClientActor` (subscribe, publish, deliver), `MqttMessageGenerator` (ACK creation), `DefaultRetainedMsgService` (expiry).

**Which upstream methods to port (Claude's Discretion):** Port only what is needed for R1 scope. Recommended subset:

```java
// Source: MqttPropertiesUtil.java [VERIFIED from codebase read]
// KEEP: session expiry, topic alias, payload format, content type,
//       response topic, correlation data, receive max, user properties,
//       pub expiry interval, subscription identifier
// SKIP: auth method/data (D-07 deferred), device-publish variants (Kafka-coupled),
//       TbQueueMsgHeaders variants (no Kafka in lightweight), will delay
```

The property IDs can be inlined as constants in a lightweight `BrokerConstants` class or in `MqttPropertiesUtil` itself — do not import the upstream `BrokerConstants` (cross-module dep). Key IDs verified:

```java
// Source: upstream BrokerConstants.java [VERIFIED]
int PAYLOAD_FORMAT_INDICATOR_PROP_ID = 1;
int PUB_EXPIRY_INTERVAL_PROP_ID = 2;
int CONTENT_TYPE_PROP_ID = 3;
int RESPONSE_TOPIC_PROP_ID = 8;
int CORRELATION_DATA_PROP_ID = 9;
int SUBSCRIPTION_IDENTIFIER_PROP_ID = 11;
int SESSION_EXPIRY_INTERVAL_PROP_ID = 17;
int ASSIGNED_CLIENT_IDENTIFIER_PROP_ID = 18;
int RECEIVE_MAXIMUM_PROP_ID = 33;
int TOPIC_ALIAS_MAX_PROP_ID = 34;
int TOPIC_ALIAS_PROP_ID = 35;
int USER_PROPERTY_PROP_ID = 38;
int DEFAULT_RECEIVE_MAXIMUM = 65535;
```

### Pattern 3: TopicAliasCtx (PROTO-09, bidirectional)

**What:** Per-session bidirectional topic alias context. `clientMappings` (int→String) resolves inbound aliases from client. `serverMappings` (String→int) tracks server-side allocations for outbound.

**When to use:** Created during CONNECT processing for MQTT 5.0 clients; `DISABLED_TOPIC_ALIASES` singleton used for 3.1.1 clients — avoids null checks throughout.

```java
// Source: TopicAliasCtx.java [VERIFIED from codebase read]
// For MQTT 5.0 clients:
TopicAliasCtx ctx = new TopicAliasCtx(true, maxTopicAlias);
// For MQTT 3.1.1 clients:
TopicAliasCtx ctx = TopicAliasCtx.DISABLED_TOPIC_ALIASES;
// stored on ClientSessionCtx.topicAliasCtx
```

The upstream `TopicAliasCtx` has no Kafka/cluster dependencies — port it verbatim. Only change: remove upstream's `DevicePublishMsg` overload (Kafka-specific), keep `PublishMsg` overload only.

Key validation: topic alias of 0 is a Protocol Error; alias > maxTopicAlias is a Protocol Error — both must throw `MqttException` and disconnect the client. [VERIFIED: TopicAliasCtx.validateTopicAlias() lines 140-147]

### Pattern 4: MqttReasonCodeResolver (PROTO-09)

**What:** Static utility mapping version context to the correct Netty `MqttConnectReturnCode` or `MqttReasonCodes.*` enum value.

**When to use:** Every place that creates a CONNACK, PUBACK, PUBREC, PUBREL, PUBCOMP, SUBACK, UNSUBACK, or DISCONNECT response.

**Key pattern verified from upstream:** [VERIFIED: MqttReasonCodeResolver.java]

```java
// CONNACK return codes differ between versions:
// MQTT 3.1.1: CONNECTION_REFUSED_SERVER_UNAVAILABLE (return code 3)
// MQTT 5.0:   CONNECTION_REFUSED_UNSPECIFIED_ERROR  (reason code 0x80)
public static MqttConnectReturnCode connectionRefusedUnspecified(ClientSessionCtx ctx) {
    return ctx.getMqttVersion() == MqttVersion.MQTT_5
        ? CONNECTION_REFUSED_UNSPECIFIED_ERROR
        : CONNECTION_REFUSED_SERVER_UNAVAILABLE;
}

// QoS ACKs: MQTT 5.0 includes reason code; MQTT 3.1.1 uses null (no reason code byte)
public static PubAck pubAckSuccess(ClientSessionCtx ctx) {
    return ctx.getMqttVersion() == MqttVersion.MQTT_5 ? PubAck.SUCCESS : null;
}
```

The `null` return for 3.1.1 clients is intentional — Netty's message builder uses it to determine whether to include the reason-code byte in the wire packet.

### Pattern 5: MqttMessageGenerator Extension for Properties

**What:** Add `MqttProperties`-aware overloads to `MqttMessageGenerator` interface so MQTT 5.0 ACKs carry reason codes and CONNACK carries 5.0 properties.

**When to use:** `ClientActor` checks version and calls the properties overload for 5.0 clients, the plain overload for 3.1.1.

**Interface additions needed:**

```java
// Source: upstream MqttMessageGenerator pattern — adapted for lightweight [VERIFIED by inspection]

// CONNACK with MQTT 5.0 properties (session expiry override, topic alias max, receive max)
MqttConnAckMessage createConnAck(MqttConnectReturnCode returnCode, boolean sessionPresent,
                                  MqttProperties properties);

// ACKs with reason codes (MQTT 5.0 only)
MqttMessage createPubAck(int packetId, MqttReasonCodes.PubAck reasonCode);
MqttMessage createPubRec(int packetId, MqttReasonCodes.PubRec reasonCode);
MqttMessage createSubAck(int packetId, List<Integer> grantedQosList, MqttProperties properties);
MqttMessage createUnsubAck(int packetId, MqttProperties properties);
MqttMessage createDisconnect(MqttReasonCodes.Disconnect reasonCode);  // broker-initiated

// PUBLISH with MQTT 5.0 properties (user props, expiry, payload format, content type, etc.)
MqttPublishMessage createPublish(String topic, int qos, byte[] payload, boolean retain,
                                  boolean dup, int packetId, MqttProperties properties);
```

Existing no-properties overloads remain for 3.1.1 clients — avoids breaking all existing callers.

### Pattern 6: Shared Subscription Delivery (PROTO-10)

**What:** After trie lookup returns matching subscriptions, group by `shareName`. Non-shared (shareName=null): deliver to all. Shared: pick one member per group via round-robin `AtomicInteger`.

**When to use:** In `DefaultMsgDispatcherService.deliverToSubscribers()`.

**Upstream approach verified:** The upstream uses a full `SharedSubscriptionProcessorImpl` with `ConcurrentHashMap<TopicSharedSubscription, SharedSubscriptionState>` and an `AtomicInteger` counter per group. [VERIFIED: SharedSubscriptionProcessorImpl.java]

**Lightweight simplified approach (D-09/D-10):**

```java
// In DefaultMsgDispatcherService.deliverToSubscribers()
// Step 1: Separate shared from non-shared subscriptions
Map<String, List<Subscription>> sharedGroups = new HashMap<>();
List<Subscription> nonShared = new ArrayList<>();
for (ValueWithTopicFilter<Subscription> match : matches) {
    Subscription sub = match.getValue();
    if (sub.getShareName() != null) {
        sharedGroups.computeIfAbsent(sub.getShareName(), k -> new ArrayList<>()).add(sub);
    } else {
        nonShared.add(sub);
    }
}

// Step 2: Deliver to all non-shared
for (Subscription sub : nonShared) { deliver(msg, sub); }

// Step 3: For each shared group, pick one via round-robin
for (Map.Entry<String, List<Subscription>> entry : sharedGroups.entrySet()) {
    Subscription picked = roundRobin(entry.getKey(), entry.getValue());
    if (picked != null) { deliver(msg, picked); }
}
```

The `roundRobin()` method uses a `ConcurrentHashMap<String, AtomicInteger>` keyed by group name, with `Math.floorMod(counter.getAndIncrement(), size)` to pick the index.

**Shared subscription parsing (from upstream):** [VERIFIED: NettyMqttConverter.java lines 93-110]

```java
// SHARED_SUBSCRIPTION_PREFIX = "$share/"
// SHARE_NAME_IDX = 7 (= "$share/".length())
public static boolean isSharedTopic(String topicFilter) {
    return topicFilter.startsWith("$share/");
}
public static String getTopicFilter(String topicFilter) {
    return isSharedTopic(topicFilter)
        ? topicFilter.substring(topicFilter.indexOf('/', 7) + 1)
        : topicFilter;
}
public static String getShareName(String topicFilter) {
    return isSharedTopic(topicFilter)
        ? topicFilter.substring(7, topicFilter.indexOf('/', 7))
        : null;
}
```

These three helpers must be added to lightweight — either in a new `MqttTopicUtil` class or in `ClientActor` where subscribe is processed.

### Pattern 7: Message Expiry Interval for Retained Messages (D-04)

**What:** Store `createdTime` on `RetainedMsg`, check expiry on delivery by comparing `createdTime + expiryMs` against `System.currentTimeMillis()`.

**When to use:** In `DefaultRetainedMsgService.getRetainedMessages()` (filter expired before returning) and in `ClientActor.processSubscribe()` (skip delivery of expired retained messages).

**Upstream verified approach:** [VERIFIED: MqttPropertiesUtil.isRetainedMsgExpired() lines 40-48]

```java
// RetainedMsg needs: createdTime (long), properties (MqttProperties)
// Expiry check:
boolean expired = createdTime + TimeUnit.SECONDS.toMillis(expiryInterval) < System.currentTimeMillis();
```

`RetainedMsg` needs two new fields: `private final long createdTime` and `private final MqttProperties properties`. For 3.1.1 retained messages, `properties = MqttProperties.NO_PROPERTIES` and `createdTime = System.currentTimeMillis()`.

Similarly, `PublishMsg` needs `private final MqttProperties properties` — set to `MqttProperties.NO_PROPERTIES` for 3.1.1 messages.

### Pattern 8: Receive Maximum Flow Control (D-04)

**What:** Track in-flight QoS 1/2 messages per session. Pause delivery when limit reached; resume on each ACK received.

**Lightweight approach (Claude's Discretion):** Store `receiveMaximum` (int) on `ClientSessionCtx`. Track in-flight count as `outboundQos1.size() + outboundQos2.size()` — these maps already exist. Before delivering a QoS 1/2 message in `processDeliver()`, check count against limit. Drop (or defer) if at limit.

**Default value:** `DEFAULT_RECEIVE_MAXIMUM = 65535` (same as upstream). Client can specify a lower value via CONNECT properties — apply `Math.min(clientRequested, 65535)`.

### Pattern 9: Subscription Identifiers (D-06)

**What:** SUBSCRIBE packet may carry a `Subscription Identifier` (1-268435455). Broker includes matching IDs in PUBLISH messages delivered to that subscription.

**Storage approach (Claude's Discretion):** Add `private final int subscriptionId` to `Subscription` class (0 = no ID). When delivering via `processDeliver()`, the `DeliverMsg` carries the subscription ID. If non-zero, add `SUBSCRIPTION_IDENTIFIER_PROP_ID` to outbound PUBLISH properties.

**MQTT 5.0 spec constraint:** A subscription ID of 0 is a Protocol Error — disconnect the client if received. [VERIFIED: upstream MqttSessionHandler check lines 186-191]

### Anti-Patterns to Avoid

- **Null MqttProperties:** Never pass `null` as properties; use `MqttProperties.NO_PROPERTIES` (Netty singleton) for 3.1.1 messages. Null properties cause NPE deep in Netty encoder.
- **Version branching at handler boundary:** Do not check `mqttVersion` in `MqttSessionHandler`. Version-aware logic belongs in `ClientActor` and `MqttMessageGenerator`. Handler only routes.
- **Hand-rolling shared subscription group state:** Do not use a list-copy-and-index approach for round-robin. The `AtomicInteger.getAndIncrement()` + `Math.floorMod()` pattern handles wrap-around and concurrent modification correctly.
- **Storing `TopicAliasCtx` on the wrong object:** Topic aliases are strictly per-connection, not per-clientId. They reset on every new TCP connection, even if the same clientId reconnects. Always tie `TopicAliasCtx` to `ClientSessionCtx` (per-connection), not to any client-ID-keyed map.
- **Forwarding raw inbound `MqttProperties` object:** The inbound `MqttProperties` from the Netty decoder is owned by the pipeline and may be released after the handler returns. Always copy or extract specific property values before passing to the actor message.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| MQTT 5.0 property encoding/decoding | Custom byte-level property parser | Netty `MqttProperties` API | Netty handles MQTT 5.0 property encoding/decoding; property IDs, variable-length integers, and UTF-8 strings are codec-handled |
| Topic alias state management | Custom per-session map with manual sync | Port upstream `TopicAliasCtx` | Already handles bidirectional mapping, slot exhaustion, alias-0 validation, and thread safety with `ConcurrentHashMap` |
| Shared subscription parsing | Custom string splitting | Port `isSharedTopic`/`getShareName`/`getTopicFilter` | Upstream helpers correctly handle edge cases like missing topic after group name |
| Reason code version mapping | Switch-case in every ACK handler | Port `MqttReasonCodeResolver` | 20+ distinct mappings across CONNACK, PUBACK, PUBREC, PUBREL, PUBCOMP, SUBACK, UNSUBACK, DISCONNECT |
| Message expiry calculation | Custom timer or TTL tracking | Port `MqttPropertiesUtil.isRetainedMsgExpired()` | The spec requires "remaining TTL" to be recalculated as `original_expiry - elapsed_time` when forwarding, not just a simple absolute timestamp check |

**Key insight:** The upstream TBMQ codebase is the source of truth for all MQTT 5.0 property handling. These utilities are battle-tested against the spec. Port them; don't rewrite.

---

## Common Pitfalls

### Pitfall 1: MQTT Version Byte vs. MqttVersion Enum
**What goes wrong:** `connectMsg.variableHeader().version()` returns the raw integer protocol level (4 for MQTT 3.1.1, 5 for MQTT 5.0). Comparing directly against `MqttVersion.MQTT_5` (an enum) fails.
**Why it happens:** Netty's `MqttConnectVariableHeader.version()` returns int, not `MqttVersion`.
**How to avoid:** Use `MqttVersion.MQTT_5.protocolLevel()` (returns 5) for comparison, or use `MqttVersion.fromProtocolNameAndLevel()` to resolve the enum.
**Warning signs:** `ClassCastException` or wrong-version behavior when a 5.0 client connects.

### Pitfall 2: Topic Alias Zero or Exceeds Maximum
**What goes wrong:** Client sends a PUBLISH with topic alias 0 (forbidden by spec) or with alias > Topic Alias Maximum advertised in CONNACK.
**Why it happens:** Clients may be misconfigured or buggy.
**How to avoid:** `TopicAliasCtx.validateTopicAlias()` throws `MqttException` on both cases. Catch it in `MqttSessionHandler.processPublish()` and disconnect with `TOPIC_ALIAS_INVALID` reason code (for MQTT 5.0) or malformed packet (for 3.1.1 which shouldn't send aliases at all).
**Warning signs:** `MqttException("Topic Alias is zero")` or `MqttException("Topic Alias ... can not be greater than...")`.

### Pitfall 3: Shared Subscription Topic Filter in Trie
**What goes wrong:** The raw `$share/group/actualtopic` string is stored in the trie. When a PUBLISH arrives on `actualtopic`, the trie won't match because it has no `$share/` entries.
**Why it happens:** Forgetting to strip the `$share/group/` prefix before calling `subscriptionRegistry.subscribe()`.
**How to avoid:** Always strip via `getTopicFilter()` before registering in the trie. Store the `shareName` on the `Subscription` object so the dispatcher can reconstruct group membership at delivery time.
**Warning signs:** Shared subscription subscribers receive zero messages despite active publishers.

### Pitfall 4: MqttProperties Forwarding on PublishMsg
**What goes wrong:** `PublishMsg` currently has no `properties` field. MQTT 5.0 publish properties (user properties, expiry, payload format, content type, response topic, correlation data) are lost during dispatch when the message transits through the queue.
**Why it happens:** `PublishMsg` was designed for 3.1.1 which has no properties.
**How to avoid:** Add `private final MqttProperties properties` to `PublishMsg` with default `MqttProperties.NO_PROPERTIES`. Extract properties from inbound PUBLISH in `MqttSessionHandler.processPublish()` and include in the `MqttPublishMsg` actor message.
**Warning signs:** User properties and content type missing from delivered PUBLISH for MQTT 5.0 subscribers.

### Pitfall 5: Receive Maximum Not Advertised in CONNACK
**What goes wrong:** Server doesn't include `Receive Maximum` in CONNACK properties, so clients default to 65535 and send a flood of QoS 1/2 messages.
**Why it happens:** Forgetting to add `RECEIVE_MAXIMUM_PROP_ID` to CONNACK `MqttProperties` for MQTT 5.0 clients.
**How to avoid:** Always include Receive Maximum in CONNACK for MQTT 5.0 clients. Read client's requested Receive Maximum from CONNECT properties, apply `Math.min(clientValue, brokerMax)`.
**Warning signs:** In-flight QoS 1/2 count exceeds expected limit; `outboundQos1` and `outboundQos2` maps grow without bound.

### Pitfall 6: Subscription Identifier of Zero
**What goes wrong:** Client sends SUBSCRIBE with Subscription Identifier = 0. Per MQTT 5.0 spec section 3.8.2.1.2, this is a Protocol Error.
**Why it happens:** Buggy client implementations.
**How to avoid:** Check for 0 value in subscribe handler; disconnect with `SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED` or `PROTOCOL_ERROR` reason code. [VERIFIED: upstream MqttSessionHandler lines 186-191 detects this]
**Warning signs:** Protocol error not caught; 0 stored as subscription ID and potentially included in outbound PUBLISH causing client to misinterpret.

### Pitfall 7: CONNACK Properties Not Set for 5.0 Clients
**What goes wrong:** Sending a plain `CONNACK` without `MqttProperties` to a MQTT 5.0 client. The client expects Topic Alias Maximum, Receive Maximum, and (if overriding) Session Expiry Interval in the CONNACK properties.
**Why it happens:** Using the existing `createConnAck(code, sessionPresent)` overload for all clients.
**How to avoid:** Always construct `MqttProperties` for MQTT 5.0 CONNACK with at minimum: `TOPIC_ALIAS_MAX_PROP_ID` (D-13), `RECEIVE_MAXIMUM_PROP_ID` (D-04), and `SESSION_EXPIRY_INTERVAL_PROP_ID = 0` only when client requested non-zero (D-02).

---

## Code Examples

Verified patterns from codebase inspection:

### Version Detection in processConnect
```java
// Source: upstream MqttSessionHandler version detection [VERIFIED: codebase read]
int versionLevel = connectMsg.variableHeader().version();
MqttVersion mqttVersion = (versionLevel == MqttVersion.MQTT_5.protocolLevel())
        ? MqttVersion.MQTT_5 : MqttVersion.MQTT_3_1_1;
sessionCtx.setMqttVersion(mqttVersion);

// For MQTT 5.0: build TopicAliasCtx with configured maximum
TopicAliasCtx aliasCtx = (mqttVersion == MqttVersion.MQTT_5)
        ? new TopicAliasCtx(true, topicAliasMax)
        : TopicAliasCtx.DISABLED_TOPIC_ALIASES;
sessionCtx.setTopicAliasCtx(aliasCtx);
```

### CONNACK with MQTT 5.0 Properties
```java
// Source: upstream ClientActor/MqttMessageGenerator pattern [VERIFIED by pattern inspection]
if (sessionCtx.getMqttVersion() == MqttVersion.MQTT_5) {
    MqttProperties connAckProps = new MqttProperties();
    MqttPropertiesUtil.addMaxTopicAliasToProps(connAckProps, topicAliasMax);
    MqttPropertiesUtil.addReceiveMaxToProps(connAckProps, receiveMaximum);
    // D-02: only include session expiry override when client requested non-zero
    int clientSessionExpiry = MqttPropertiesUtil.getConnectSessionExpiryIntervalValue(connectProps, Integer.MAX_VALUE);
    if (clientSessionExpiry > 0) {
        MqttPropertiesUtil.addSessionExpiryIntervalToProps(connAckProps, 0);
    }
    channel.writeAndFlush(messageGenerator.createConnAck(CONNECTION_ACCEPTED, false, connAckProps));
} else {
    channel.writeAndFlush(messageGenerator.createConnAck(CONNECTION_ACCEPTED, false));
}
```

### Inbound Topic Alias Resolution
```java
// Source: TopicAliasCtx.getTopicNameByAlias() [VERIFIED from codebase read]
// Called in MqttSessionHandler.processPublish() for MQTT 5.0 clients
String resolvedTopic = sessionCtx.getTopicAliasCtx().getTopicNameByAlias(publishMsg);
String topicName = (resolvedTopic != null) ? resolvedTopic : publishMsg.getTopicName();
// If resolvedTopic is null: no alias present, use the topic as-is
// If resolvedTopic is non-null: alias was present and resolved
```

### Shared Subscription Round-Robin
```java
// Source: upstream SharedSubscriptionProcessorImpl [VERIFIED from codebase read]
// In DefaultMsgDispatcherService — simplified for lightweight (no Kafka cluster)
private final ConcurrentHashMap<String, AtomicInteger> sharedGroupCounters = new ConcurrentHashMap<>();

private Subscription pickFromSharedGroup(String groupKey, List<Subscription> members) {
    AtomicInteger counter = sharedGroupCounters.computeIfAbsent(groupKey, k -> new AtomicInteger(0));
    int size = members.size();
    for (int i = 0; i < size; i++) {
        int index = Math.floorMod(counter.getAndIncrement(), size);
        Subscription sub = members.get(index);
        if (sub.getSessionCtx().getState() == SessionState.CONNECTED) {
            return sub;
        }
    }
    return null; // no connected member found
}
```

### PUBACK with Reason Code (MQTT 5.0)
```java
// Source: MqttReasonCodeResolver pattern [VERIFIED from codebase read]
MqttReasonCodes.PubAck reasonCode = MqttReasonCodeResolver.pubAckSuccess(sessionCtx);
// reasonCode == PubAck.SUCCESS for MQTT 5.0, null for MQTT 3.1.1
channel.writeAndFlush(messageGenerator.createPubAck(packetId, reasonCode));
```

### SUBACK with Reason Codes (MQTT 5.0)
```java
// MQTT 5.0: reason codes in SUBACK use MqttReasonCodes.SubAck values
// MQTT 3.1.1: return codes are plain int (0, 1, 2 for QoS; 0x80 for failure)
// Source: upstream NettyMqttConverter + MqttReasonCodeResolver [VERIFIED]
List<Integer> returnCodes = new ArrayList<>();
for (TopicSub sub : topicSubs) {
    if (aclDenied) {
        returnCodes.add(sessionCtx.getMqttVersion() == MqttVersion.MQTT_5
                ? MqttReasonCodes.SubAck.NOT_AUTHORIZED.value() & 0xFF
                : 0x80);
    } else {
        returnCodes.add(grantedQos); // same numeric value works for both versions
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| MQTT 3.1.1-only ACKs (no reason codes) | MQTT 5.0 reason codes in all ACKs | Phase 6 | All ACK-generating code must be version-aware |
| Simple topic matching | Topic alias resolution before dispatch | Phase 6 | `MqttSessionHandler.processPublish()` must resolve alias before extracting topic name |
| `MqttMessage` for PubAck/PubRec etc. | `MqttReasonCodeAndPropertiesVariableHeader`-based for MQTT 5.0 | Netty 4.1.68+ | Generator must use different Netty builder paths per version |

**Deprecated/outdated:**
- `DISCONNECT` handling: For MQTT 5.0, `processDisconnect()` receives a `MqttReasonCodeAndPropertiesVariableHeader`, not the simple variable header. The handler must cast based on version.

---

## Open Questions

1. **Retained Message Properties Field Type**
   - What we know: `RetainedMsg.properties` should hold `MqttProperties` for expiry and other MQTT 5.0 properties
   - What's unclear: Whether to use `MqttProperties` (Netty type) or a lightweight wrapper to avoid Netty dependency leaking deeper
   - Recommendation: Use `MqttProperties` directly — Netty is already a core dependency throughout the codebase; wrapping adds complexity with no benefit

2. **Shared Subscription Cleanup on Last Member**
   - What we know: When the last member of a shared group unsubscribes, the round-robin counter entry becomes stale
   - What's unclear: Whether to clean up the counter map immediately on unsubscribe or lazily
   - Recommendation: Lazy cleanup — counter state is harmless when group is empty; premature cleanup adds lock complexity. Counter resets naturally when group re-forms.

3. **DeliverMsg Subscription Identifier**
   - What we know: `DeliverMsg` currently carries `PublishMsg` + `int deliveryQos`
   - What's unclear: Whether to add `subscriptionId` to `DeliverMsg` or to `Subscription`
   - Recommendation: Add `subscriptionId` to `DeliverMsg` — the dispatcher knows which subscription matched, so it can include the ID when constructing the deliver message without requiring the actor to look it up

---

## Environment Availability

Step 2.6: SKIPPED — Phase 6 is a code-only change within the existing build environment. No new external services, databases, or CLIs required. All dependencies are JVM libraries.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test (existing) |
| Config file | `lightweight/pom.xml` Surefire plugin |
| Quick run command | `mvn -pl lightweight test -Dtest=Mqtt5*IntegrationTest -f /home/dlandiak/projects/gsd/tbmq/pom.xml` |
| Full suite command | `mvn -pl lightweight test -f /home/dlandiak/projects/gsd/tbmq/pom.xml` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PROTO-08 | MQTT 5.0 and 3.1.1 clients coexist on same port; each receives version-appropriate CONNACK | integration | `mvn -pl lightweight test -Dtest=Mqtt5VersionNegotiationTest -f .../pom.xml` | Wave 0 |
| PROTO-08 | MQTT 5.0 CONNACK includes Topic Alias Maximum and Receive Maximum in properties | integration | `mvn -pl lightweight test -Dtest=Mqtt5VersionNegotiationTest -f .../pom.xml` | Wave 0 |
| PROTO-09 | Session Expiry Interval override in CONNACK when client requests non-zero | integration | `mvn -pl lightweight test -Dtest=Mqtt5PropertiesTest -f .../pom.xml` | Wave 0 |
| PROTO-09 | User properties forwarded from publisher to subscriber | integration | `mvn -pl lightweight test -Dtest=Mqtt5PropertiesTest -f .../pom.xml` | Wave 0 |
| PROTO-09 | Topic alias resolved correctly for repeated topics | integration | `mvn -pl lightweight test -Dtest=Mqtt5TopicAliasTest -f .../pom.xml` | Wave 0 |
| PROTO-09 | Full reason codes in PUBACK, PUBREC, PUBREL, PUBCOMP for QoS 1/2 | integration | `mvn -pl lightweight test -Dtest=Mqtt5ReasonCodeTest -f .../pom.xml` | Wave 0 |
| PROTO-09 | Message Expiry Interval enforced on retained messages | integration | `mvn -pl lightweight test -Dtest=Mqtt5PropertiesTest#testMessageExpiryInterval -f .../pom.xml` | Wave 0 |
| PROTO-10 | Shared subscription delivers message to exactly one member | integration | `mvn -pl lightweight test -Dtest=Mqtt5SharedSubscriptionTest -f .../pom.xml` | Wave 0 |
| PROTO-10 | Shared subscriptions work for MQTT 3.1.1 clients (D-11) | integration | `mvn -pl lightweight test -Dtest=Mqtt5SharedSubscriptionTest#testSharedSub311Client -f .../pom.xml` | Wave 0 |

### Sampling Rate
- **Per task commit:** Run the specific test class for the feature just implemented
- **Per wave merge:** `mvn -pl lightweight test -f /home/dlandiak/projects/gsd/tbmq/pom.xml`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps

The following test files do not yet exist and must be created in Wave 0 before feature implementation:

- [ ] `lightweight/src/test/java/.../mqtt/Mqtt5VersionNegotiationTest.java` — covers PROTO-08
- [ ] `lightweight/src/test/java/.../mqtt/Mqtt5PropertiesTest.java` — covers PROTO-09 (session expiry, user props, message expiry, payload format, content type, response topic, correlation data)
- [ ] `lightweight/src/test/java/.../mqtt/Mqtt5TopicAliasTest.java` — covers PROTO-09 (topic alias)
- [ ] `lightweight/src/test/java/.../mqtt/Mqtt5ReasonCodeTest.java` — covers PROTO-08/09 (reason codes in ACKs)
- [ ] `lightweight/src/test/java/.../mqtt/Mqtt5SharedSubscriptionTest.java` — covers PROTO-10
- [ ] `lightweight/pom.xml` — add `org.eclipse.paho.mqttv5.client` test dependency

**Test client for MQTT 5.0:** Use `org.eclipse.paho.mqttv5.client.MqttClient` from `org.eclipse.paho.mqttv5.client` (already managed in root POM at version 1.2.5). The AbstractMqttIntegrationTest pattern applies — extend with an MQTT 5.0-aware base class `AbstractMqtt5IntegrationTest` that uses `org.eclipse.paho.mqttv5.client.MqttClient` and `MqttConnectionOptions` (the v5 API class name differs from v3's `MqttConnectOptions`).

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no — unchanged from Phase 4 | username/password + X.509 mTLS already implemented |
| V3 Session Management | yes — MQTT 5.0 session expiry | Always override to 0 (D-01); D-03 ignores DISCONNECT expiry |
| V4 Access Control | no — unchanged from Phase 4 | ACL patterns already applied in subscribe/publish |
| V5 Input Validation | yes | Topic alias bounds checking; subscription ID=0 rejection; shared topic format validation |
| V6 Cryptography | no | No new crypto operations |

### Known Threat Patterns for MQTT 5.0

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Topic alias exhaustion attack (send max aliases, all colliding) | Denial of Service | `topicAlias > maxTopicAlias` disconnects with `TOPIC_ALIAS_INVALID`; alias 0 disconnects similarly |
| Session expiry extension via DISCONNECT properties | Elevation of Privilege | D-03: always ignore session expiry in DISCONNECT; clean up immediately |
| Shared subscription group impersonation | Spoofing | Group name is subscriber-controlled string; no server-side auth on group names — this is by MQTT spec design and acceptable |
| Malformed $share topic (no group name or no topic) | Tampering | `getShareName()` IndexOutOfBoundsException caught and re-thrown as `RuntimeException` — must result in DISCONNECT not NPE |
| User properties flooding (hundreds of key-value pairs) | Denial of Service | Netty `MqttProperties` has no internal limit; rely on `TBMQ_MQTT_MAX_PAYLOAD_SIZE` which caps total packet size |

---

## Sources

### Primary (HIGH confidence)
- Upstream `application/src/main/java/.../util/MqttPropertiesUtil.java` — all MQTT 5.0 property methods [VERIFIED: read in full]
- Upstream `application/src/main/java/.../session/TopicAliasCtx.java` — bidirectional alias context [VERIFIED: read in full]
- Upstream `application/src/main/java/.../util/MqttReasonCodeResolver.java` — version-aware reason codes [VERIFIED: read in full]
- Upstream `application/src/main/java/.../adaptor/NettyMqttConverter.java` — shared subscription helpers [VERIFIED: read lines 80-110]
- Upstream `application/src/main/java/.../subscription/shared/SharedSubscriptionProcessorImpl.java` — round-robin logic [VERIFIED: read in full]
- Lightweight `server/MqttSessionHandler.java` — existing CONNECT handler to extend [VERIFIED: read in full]
- Lightweight `session/ClientSessionCtx.java` — fields to add [VERIFIED: read in full]
- Lightweight `actors/client/ClientActor.java` — actor message handlers to extend [VERIFIED: read in full]
- Lightweight `service/dispatch/DefaultMsgDispatcherService.java` — delivery loop to extend [VERIFIED: read in full]
- Upstream `common/data/.../BrokerConstants.java` — all MQTT 5.0 property ID values [VERIFIED: grep confirmed]
- `lightweight/pom.xml` — confirmed only Paho v3 in test scope; v5 must be added [VERIFIED: read in full]
- Root `pom.xml` — confirmed `org.eclipse.paho.mqttv5.client` 1.2.5 in dependency management [VERIFIED: grep]

---

## Assumptions Log

**If this table is empty:** All claims in this research were verified or cited — no user confirmation needed.

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Netty 4.1.130 fully supports all MQTT 5.0 property IDs and `MqttReasonCodes` needed for this phase | Standard Stack | [ASSUMED] — Netty 4.1.x MQTT 5.0 support was introduced around 4.1.68; version 4.1.130 almost certainly includes it but specific codec version details not explicitly verified via API doc during this session |

All other claims are VERIFIED against the live codebase.

---

## Metadata

**Confidence breakdown:**
- Standard Stack: HIGH — verified against pom.xml and Netty codec imports in existing code
- Architecture: HIGH — all patterns verified against upstream source files and lightweight code
- Pitfalls: HIGH — derived from upstream code patterns and spec citations
- Security: MEDIUM — ASVS categories from spec analysis; threat patterns from code inspection

**Research date:** 2026-04-11
**Valid until:** 2026-05-11 (stable domain — MQTT spec and Netty codec don't change frequently)
