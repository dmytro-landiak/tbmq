# Phase 3: Message Dispatch - Context

**Gathered:** 2026-04-08
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase refactors the Phase 2 inline delivery path into a standalone in-process dispatch service behind an abstracted interface, replaces the exact-match subscription registry with a concurrent wildcard subscription trie supporting `+` and `#` wildcards, adds a retained message trie for wildcard-aware retained message delivery, and introduces backpressure via a bounded dispatch queue — ensuring Netty I/O threads are never blocked. No Kafka, no external dependencies. Single-node only.

</domain>

<decisions>
## Implementation Decisions

### Dispatch Architecture
- **D-01:** Refactor inline `deliverToSubscribers()` in ClientActor into an abstracted `MsgDispatcherService` interface. Publisher's actor calls the dispatch service instead of directly iterating subscribers and writing to channels.
- **D-02:** Dispatch queue uses `LinkedBlockingQueue` behind a `PublishMsgQueueFactory` interface, per STATE.md decision. Upgrade to LMAX Disruptor only if benchmarks show saturation — the interface allows swapping without changing consumers.
- **D-03:** Copy and adapt TBMQ's dispatch patterns (`MsgDispatcherService`, `DownLinkProxy`, `BasicDownLinkProcessor`) into lightweight, trimming multi-node routing and Kafka references.

### Backpressure Strategy
- **D-04:** Bounded queue with configurable capacity (env var: `TBMQ_DISPATCH_QUEUE_CAPACITY`, default TBD by planner). When queue is full, new messages are dropped and a `dropped_msgs` Micrometer counter is incremented. Publisher still receives normal QoS acks (PUBACK/PUBREC) — QoS acknowledgment confirms broker receipt, not subscriber delivery. No actor blocking, no cascading stalls.

### Subscription Matching
- **D-05:** Replace `DefaultSubscriptionRegistry` (ConcurrentHashMap exact-match) with a concurrent wildcard subscription trie, copied and adapted from TBMQ's `ConcurrentMapSubscriptionTrie`. Supports `+` (single-level) and `#` (multi-level) wildcards per MQTT 3.1.1 spec.
- **D-06:** `$SYS/` topics must not be matched by wildcard subscriptions (`#`, `+/...`) unless the subscription explicitly starts with `$SYS/`, per MQTT spec section 4.7.2.
- **D-07:** Trie returns `List<ValueWithTopicFilter<Subscription>>` to preserve which topic filter matched — needed for QoS downgrade (min of publish QoS and subscription QoS per filter).

### Retained Message Wildcard Delivery
- **D-08:** Store retained messages in a separate retained message trie (same trie data structure as subscriptions but storing `RetainedMessage` values). On subscribe with a wildcard filter, traverse the retained trie to collect all matching retained messages for immediate delivery.
- **D-09:** Copy and adapt TBMQ's `ConcurrentMapRetainMsgTrie` pattern if it exists, otherwise reuse the generic `ConcurrentMapSubscriptionTrie<RetainedMessage>` with topic-as-key insertion.

### Dispatch Threading
- **D-10:** Claude's discretion on consumer thread pool sizing and strategy. Should consider: delivery is non-blocking (`channel.writeAndFlush()`), subscription trie lookup is O(topic-depth), and the target scale is tens of thousands of concurrent connections.

### Claude's Discretion
- Exact consumer thread pool type and sizing (fixed, ForkJoinPool reuse, or single-thread)
- Default queue capacity value
- Class names and package layout for dispatch service components (follow TBMQ naming conventions)
- Whether to extract a generic trie interface shared between subscription trie and retained message trie, or keep them as separate copies
- Integration test design for wildcard matching and backpressure scenarios

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### TBMQ Dispatch Patterns (copy and adapt)
- `application/src/main/java/org/thingsboard/mqtt/broker/service/processing/MsgDispatcherService.java` — Dispatch interface
- `application/src/main/java/org/thingsboard/mqtt/broker/service/processing/MsgDispatcherServiceImpl.java` — Dispatch implementation (lines 103-127)
- `application/src/main/java/org/thingsboard/mqtt/broker/service/processing/downlink/DownLinkProxy.java` — Routing abstraction
- `application/src/main/java/org/thingsboard/mqtt/broker/service/processing/downlink/DownLinkProxyImpl.java` — Local/remote dispatch (lines 41-56)
- `application/src/main/java/org/thingsboard/mqtt/broker/service/processing/downlink/basic/BasicDownLinkProcessor.java` — Delivery processor interface
- `application/src/main/java/org/thingsboard/mqtt/broker/service/processing/downlink/basic/BasicDownLinkProcessorImpl.java` — Delivery implementation (lines 43-56)
- `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/provider/PublishMsgQueueFactory.java` — Queue factory abstraction

### TBMQ Subscription Trie (copy and adapt)
- `application/src/main/java/org/thingsboard/mqtt/broker/service/subscription/ConcurrentMapSubscriptionTrie.java` — Wildcard trie implementation (lines 64-108 for get/match)
- `application/src/main/java/org/thingsboard/mqtt/broker/service/subscription/SubscriptionTrie.java` — Trie interface

### TBMQ Retained Message Patterns
- `application/src/main/java/org/thingsboard/mqtt/broker/service/mqtt/retain/` — Retained message handling patterns
- `application/src/main/java/org/thingsboard/mqtt/broker/service/subscription/ConcurrentMapRetainMsgTrie.java` — Retained message trie (if exists)

### Lightweight Current Code (to refactor)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java` — Inline delivery at lines 320-453 (deliverToSubscribers)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/DefaultSubscriptionRegistry.java` — Current exact-match registry to replace
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/SubscriptionRegistry.java` — Current interface
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/retain/RetainedMsgService.java` — Current retained message service to extend
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java` — Netty handler (publish dispatch at lines 152-166)

### Architecture & Planning
- `.planning/research/ARCHITECTURE.md` — Component boundaries and dispatch queue options
- `.planning/PROJECT.md` — Core value, constraints, key decisions
- `.planning/REQUIREMENTS.md` — INFR-01, INFR-04
- `.planning/phases/01-foundation/01-CONTEXT.md` — Phase 1 decisions
- `.planning/phases/02-core-protocol-mqtt-3-1-1/02-CONTEXT.md` — Phase 2 decisions (D-04 through D-07 directly feed Phase 3)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ConcurrentMapSubscriptionTrie` in main TBMQ — battle-tested wildcard trie with ConcurrentHashMap nodes, ReadWriteLock, stack-based traversal. Copy and adapt.
- `MsgDispatcherService` / `DownLinkProxy` / `BasicDownLinkProcessor` in main TBMQ — dispatch abstraction pattern. Strip multi-node routing, keep interface shape.
- `PublishMsgQueueFactory` in main TBMQ — queue factory interface. Adapt for in-process LinkedBlockingQueue.
- `BrokerMetricsService` in lightweight — Micrometer metrics already wired, add `dropped_msgs` counter.

### Established Patterns
- Actor-per-client with ForkJoinPool executor — Netty I/O threads never blocked
- `channel.writeAndFlush()` is non-blocking (Netty handles async write)
- Spring `@Component`/`@Service` with `@RequiredArgsConstructor`
- Interface + `Default` prefix implementation naming
- Copy from TBMQ and trim (per user feedback)

### Integration Points
- `ClientActor.deliverToSubscribers()` — refactor to call `MsgDispatcherService` instead of inline delivery
- `DefaultSubscriptionRegistry` — replace implementation behind `SubscriptionRegistry` interface (interface may need method signature updates for wildcard return type)
- `RetainedMsgService` — extend to use retained message trie for wildcard subscribe delivery
- Existing integration tests — update to enable wildcard subscription tests (currently exact-match only)

</code_context>

<specifics>
## Specific Ideas

- TBMQ's trie uses `BrokerConstants.MULTI_LEVEL_WILDCARD` ("#") and `BrokerConstants.SINGLE_LEVEL_WILDCARD` ("+") — define equivalent constants in lightweight
- Trie's `get()` method uses a stack-based DFS traversal that avoids recursion (important for deeply nested topics)
- `$SYS/` topic exclusion from wildcard matching is handled in trie's `get()` method via `notStartingWith$()` check
- RetainedMsgTrie needs reverse lookup: given a topic filter (possibly with wildcards), find all retained messages whose topic matches. This is the inverse of subscription matching — subscription trie matches "topic → filters", retained trie matches "filter → topics"
- Single-node simplification: no need for TBMQ's `DownLinkProxy` local-vs-remote routing — all delivery is local. But keep the interface for clean separation of concerns.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 03-message-dispatch*
*Context gathered: 2026-04-08*
