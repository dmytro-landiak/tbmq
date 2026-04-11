# Phase 6: MQTT 5.0 - Context

**Gathered:** 2026-04-11
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase adds MQTT 5.0 protocol support to the lightweight broker: version negotiation so 5.0 and 3.1.1 clients coexist on the same port, MQTT 5.0 properties (session expiry, user properties, message expiry, receive maximum, topic aliases, payload format, content type, response topic, correlation data, subscription identifiers), full reason codes in all ACK/response packets, and shared subscriptions (`$share/group/topic`) with round-robin delivery. Enhanced authentication (SCRAM/AUTH packets) is explicitly deferred to R2.

</domain>

<decisions>
## Implementation Decisions

### Session Expiry
- **D-01:** Accept Session Expiry Interval from CONNECT but always treat as 0 — session ends immediately on disconnect. The broker is allowed to override session expiry per spec. R1 has no persistent sessions.
- **D-02:** Include Session Expiry Interval = 0 in CONNACK properties only when the client requested a non-zero value — signals the override without adding bytes to every CONNACK.
- **D-03:** Ignore Session Expiry Interval in DISCONNECT packets — always clean up immediately, consistent with D-01.

### Properties Coverage
- **D-04:** Fully support: User Properties (forwarded through message pipeline), Message Expiry Interval (TTL for retained messages), Receive Maximum (per-session in-flight QoS 1/2 limit), Payload Format Indicator, and Content Type.
- **D-05:** Pass-through without interpretation: Response Topic and Correlation Data on PUBLISH — enables client-to-client request-response patterns.
- **D-06:** Support Subscription Identifiers — client assigns integer IDs to subscriptions, broker includes matching IDs in delivered PUBLISH messages.
- **D-07:** Defer Enhanced Authentication (SCRAM/challenge-response via AUTH packets) to R2. R1 has username/password and X.509 mTLS from Phase 4.
- **D-08:** Full MQTT 5.0 reason codes in all packet types — CONNACK, PUBACK, PUBREC, PUBREL, PUBCOMP, SUBACK, UNSUBACK, DISCONNECT. No shortcuts.

### Shared Subscriptions
- **D-09:** Round-robin distribution strategy for `$share/group/topic` — AtomicInteger counter per group, rotate through active members sequentially.
- **D-10:** Shared subscriptions stored in the existing subscription trie — strip `$share/group/` prefix, store the actual topic filter with a group name annotation on the Subscription object. Trie matching works unchanged; shared vs. non-shared resolved at delivery time by grouping matches by share name and picking one per group.
- **D-11:** Shared subscriptions available to both MQTT 5.0 and MQTT 3.1.1 clients — matches upstream TBMQ behavior.

### Topic Aliases
- **D-12:** Bidirectional topic alias support — client sends aliases to broker AND broker allocates aliases for outbound PUBLISH to client. Port upstream's `TopicAliasCtx` pattern with ConcurrentHashMap for both directions.
- **D-13:** Default Topic Alias Maximum = 10 per connection. Configurable via env var (`TBMQ_TOPIC_ALIAS_MAX`). Matches upstream TBMQ default.
- **D-14:** Server-side alias allocation uses a minimum topic name length threshold — only alias topics longer than N characters. Configurable via env var. Avoids wasting alias slots on short topics.

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

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### TBMQ MQTT 5.0 Patterns (copy and adapt)
- `application/src/main/java/org/thingsboard/mqtt/broker/util/MqttPropertiesUtil.java` — 428-line properties utility: extraction, creation, and validation of all MQTT 5.0 properties
- `application/src/main/java/org/thingsboard/mqtt/broker/session/TopicAliasCtx.java` — 175-line bidirectional topic alias context with ConcurrentHashMap mappings
- `application/src/main/java/org/thingsboard/mqtt/broker/server/MqttSessionHandler.java` — Version negotiation (lines 308-312), version-aware rate limiting (lines 263-276), properties extraction from CONNECT (lines 152-170)
- `application/src/main/java/org/thingsboard/mqtt/broker/adaptor/NettyMqttConverter.java` — Shared subscription parsing (lines 93-110): `isSharedTopic()`, `getTopicFilter()`, `getShareName()`

### TBMQ Shared Subscription Implementation
- `application/src/main/java/org/thingsboard/mqtt/broker/service/subscription/shared/SharedSubscription.java` — Shared subscription model
- `application/src/main/java/org/thingsboard/mqtt/broker/service/subscription/shared/SharedSubscriptionProcessor.java` — Group routing and distribution logic

### TBMQ Reason Code Patterns
- `application/src/main/java/org/thingsboard/mqtt/broker/util/MqttReasonCodeResolver.java` — Reason code resolution for all packet types (if exists, otherwise check MqttPropertiesUtil)

### Lightweight Current Code (to extend)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java` — Add version extraction, properties handling
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/ClientSessionCtx.java` — Add mqttVersion, topicAliasCtx, receiveMaximum fields
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/MqttMessageGenerator.java` — Add MqttProperties parameter to all ACK creation methods
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java` — Add version-aware branching, shared subscription delivery
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/Subscription.java` — Add optional shareName field
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/ConcurrentMapSubscriptionTrie.java` — No trie changes needed (shared subs use same matching)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/retain/DefaultRetainedMsgService.java` — Add message expiry interval enforcement

### Project Context
- `.planning/PROJECT.md` — Core value, constraints, no-persistence-in-R1, clean-session-only
- `.planning/REQUIREMENTS.md` — PROTO-08, PROTO-09, PROTO-10
- `.planning/phases/02-core-protocol-mqtt-3-1-1/02-CONTEXT.md` — Actor-per-client model, QoS state machine, clean session design
- `.planning/phases/03-message-dispatch/03-CONTEXT.md` — Subscription trie, dispatch architecture, backpressure
- `.planning/phases/05-websocket-transport/05-CONTEXT.md` — Subprotocol negotiation (mqttv3.1,mqtt already advertised), handler factory

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `MqttPropertiesUtil` (upstream) — 428-line utility covering all MQTT 5.0 properties. Copy and trim to supported set.
- `TopicAliasCtx` (upstream) — 175-line bidirectional alias context. Port with minimal changes.
- `NettyMqttConverter.isSharedTopic/getShareName/getTopicFilter` (upstream) — Shared subscription parsing helpers. Copy directly.
- `ConcurrentMapSubscriptionTrie` (lightweight) — Already handles wildcard matching. No changes needed for shared subscriptions.
- `MqttSessionHandlerFactory` (lightweight) — Handler factory from Phase 5. Extend with new 5.0 dependencies.
- Netty 4.1.130 codec — Already has full MQTT 5.0 support: `MqttProperties`, `MqttReasonCodes`, `MqttVersion`, `MqttReasonCodeAndPropertiesVariableHeader`

### Established Patterns
- Actor-per-client with ForkJoinPool executor — version-aware branching goes inside ClientActor
- Interface + `Default` prefix naming convention
- Copy from TBMQ and trim for lightweight (per user feedback from prior phases)
- SmartLifecycle ordering, `@RequiredArgsConstructor` for DI
- Env var configuration: `"${ENV_VAR_NAME:default_value}"` in YAML

### Integration Points
- `MqttSessionHandler.processConnect()` — Add version extraction from `connectMsg.variableHeader().version()`
- `ClientSessionCtx` — Add `mqttVersion`, `topicAliasCtx`, `receiveMaximum`, `subscriptionIdentifiers` fields
- `MqttMessageGenerator` — All ACK methods need MqttProperties parameter for 5.0 clients
- `Subscription` class — Add optional `shareName` and `subscriptionId` fields
- `DefaultMsgDispatcherService` — Add shared subscription group routing at delivery time
- `DefaultRetainedMsgService` — Add message expiry check on retained message delivery
- `tbmq-lightweight.yml` — Add topic alias max, receive maximum defaults

</code_context>

<specifics>
## Specific Ideas

- Netty's `MqttConnectMessage.variableHeader().version()` returns the protocol level (4 for 3.1.1, 5 for 5.0) — use this for version detection, same as upstream line 308-312
- `TopicAliasCtx.DISABLED_TOPIC_ALIASES` singleton used for MQTT 3.1.1 clients — avoids null checks
- Shared subscription delivery: after trie returns all matching subscriptions, group by `shareName` (null = non-shared, deliver to all; non-null = shared group, pick one via round-robin)
- User Properties are `MqttProperties.UserProperties` (list of StringPair) — forward on PUBLISH by copying from inbound to outbound message
- Message Expiry Interval on retained messages: store the original expiry + timestamp at publish time, compute remaining TTL on delivery, skip if expired
- Receive Maximum flow control: track in-flight QoS 1/2 messages per session with an AtomicInteger; pause delivery when limit hit, resume on ACK
- For MQTT 3.1.1 clients, all properties are simply absent — no special handling needed beyond version check

</specifics>

<deferred>
## Deferred Ideas

- **Enhanced Authentication (SCRAM/AUTH packets)** — Multi-round challenge-response auth. Significant complexity. Deferred to R2. R1 has username/password and X.509 mTLS.

</deferred>

---

*Phase: 06-mqtt-5-0*
*Context gathered: 2026-04-11*
