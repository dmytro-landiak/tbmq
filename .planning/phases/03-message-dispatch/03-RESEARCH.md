# Phase 3: Message Dispatch - Research

**Researched:** 2026-04-08
**Domain:** In-process MQTT message dispatch, wildcard subscription trie, retained message trie, backpressure
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Refactor inline `deliverToSubscribers()` in ClientActor into an abstracted `MsgDispatcherService` interface. Publisher's actor calls the dispatch service instead of directly iterating subscribers and writing to channels.
- **D-02:** Dispatch queue uses `LinkedBlockingQueue` behind a `PublishMsgQueueFactory` interface. Upgrade to LMAX Disruptor only if benchmarks show saturation — the interface allows swapping without changing consumers.
- **D-03:** Copy and adapt TBMQ's dispatch patterns (`MsgDispatcherService`, `DownLinkProxy`, `BasicDownLinkProcessor`) into lightweight, trimming multi-node routing and Kafka references.
- **D-04:** Bounded queue with configurable capacity (env var: `TBMQ_DISPATCH_QUEUE_CAPACITY`). When queue is full, new messages are dropped and a `dropped_msgs` Micrometer counter is incremented. Publisher still receives normal QoS acks. No actor blocking, no cascading stalls.
- **D-05:** Replace `DefaultSubscriptionRegistry` with a concurrent wildcard subscription trie copied and adapted from TBMQ's `ConcurrentMapSubscriptionTrie`. Supports `+` and `#` wildcards per MQTT 3.1.1 spec.
- **D-06:** `$SYS/` topics must not be matched by wildcard subscriptions unless the subscription explicitly starts with `$SYS/`.
- **D-07:** Trie returns `List<ValueWithTopicFilter<Subscription>>` to preserve which topic filter matched — needed for QoS downgrade.
- **D-08:** Store retained messages in a retained message trie. On subscribe with a wildcard filter, traverse the retained trie to collect all matching retained messages for immediate delivery.
- **D-09:** Copy and adapt TBMQ's `ConcurrentMapRetainMsgTrie` pattern.
- **D-10 (Claude's Discretion):** Consumer thread pool sizing and strategy.

### Claude's Discretion

- Exact consumer thread pool type and sizing (fixed, ForkJoinPool reuse, or single-thread)
- Default queue capacity value
- Class names and package layout for dispatch service components (follow TBMQ naming conventions)
- Whether to extract a generic trie interface shared between subscription trie and retained message trie, or keep them as separate copies
- Integration test design for wildcard matching and backpressure scenarios

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| INFR-01 | Broker uses in-process message dispatch (queue-based) to route messages between publisher and subscriber sessions, replacing Kafka | LinkedBlockingQueue-backed dispatch queue; dispatch consumer thread sends to subscriber channels via DELIVER_MSG actor message |
| INFR-04 | Message dispatch interface is abstracted to allow swapping queue implementations without refactoring consumers | `PublishMsgQueueFactory` interface with `LinkedBlockingQueueFactory` default implementation; `MsgDispatcherService` interface injected into ClientActor |
</phase_requirements>

---

## Summary

Phase 3 refactors the existing inline delivery path in `ClientActor.deliverToSubscribers()` into a proper dispatch service backed by a bounded `LinkedBlockingQueue`. The publisher's actor submits a `DispatchMsg` to the queue; a separate consumer thread pool reads from the queue, performs wildcard-aware trie lookup, and sends `DeliverMsg` actor messages to each subscriber's actor mailbox. Netty I/O threads never touch delivery logic.

The existing exact-match `DefaultSubscriptionRegistry` is replaced by a `ConcurrentMapSubscriptionTrie` copy from TBMQ (identical algorithm, trimmed dependencies). The `DefaultRetainedMsgService` backed by `ConcurrentHashMap` is replaced by a `ConcurrentMapRetainMsgTrie` copy from TBMQ, enabling wildcard retained message delivery on subscribe.

Both TBMQ trie classes are battle-tested. The copy-and-adapt strategy (per user feedback) is straightforward because the dependencies being trimmed are all Kafka/protobuf/StatsManager references that don't exist in the lightweight module. The core trie algorithm — stack-based DFS traversal, `ConcurrentHashMap` nodes, `ReadWriteLock` for clear operations — is copied verbatim.

**Primary recommendation:** Copy TBMQ's four canonical files (`ConcurrentMapSubscriptionTrie`, `ConcurrentMapRetainMsgTrie`, `RetainMsgTrie`, `SubscriptionTrie`), strip the `StatsManager` constructor parameter and `@Value`-injected config, then wire them into the new dispatch service.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `java.util.concurrent.LinkedBlockingQueue` | JDK 17 (built-in) | Bounded dispatch queue | Zero dependency; fair, blocking producer/consumer; straightforward backpressure |
| `java.util.concurrent.Executors.newFixedThreadPool` | JDK 17 (built-in) | Consumer thread pool | Predictable thread count; straightforward shutdown |
| `io.micrometer.core:micrometer-core` | via Spring Boot BOM | `dropped_msgs` counter | Already wired in `BrokerMetricsService` |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `com.google.guava:guava` | 33.1.0-jre | `Sets.newConcurrentHashSet()` for trie node values | Used in TBMQ `ConcurrentMapSubscriptionTrie` — copy exact pattern |
| `java.util.concurrent.locks.ReadWriteLock` | JDK 17 | Trie clear operation guard | Existing pattern in both TBMQ tries |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `LinkedBlockingQueue` | LMAX Disruptor | Disruptor has lower latency at saturation but is a new dependency; D-02 explicitly defers this |
| Fixed thread pool | ForkJoinPool (common pool) | ForkJoinPool risks blocking the shared pool; fixed pool with named threads is easier to monitor |
| Fixed thread pool | Single consumer thread | Single thread is simpler but is a bottleneck; 2-4 threads handles burst better |

**No additional installation needed.** All libraries already in scope via Spring Boot BOM or existing `pom.xml`.

---

## Architecture Patterns

### Recommended Package Layout

```
lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/
├── service/
│   ├── dispatch/
│   │   ├── MsgDispatcherService.java          # interface
│   │   ├── DefaultMsgDispatcherService.java   # impl (D-01: adapter: queue submit + QoS ack)
│   │   ├── PublishMsgQueueFactory.java         # interface (D-04: queue abstraction)
│   │   └── LinkedBlockingQueueFactory.java     # default impl
│   ├── subscription/
│   │   ├── SubscriptionTrie.java              # interface (copy TBMQ)
│   │   ├── ConcurrentMapSubscriptionTrie.java  # impl (copy TBMQ, strip StatsManager)
│   │   ├── SubscriptionRegistry.java          # existing interface — update getSubscriptions signature
│   │   ├── DefaultSubscriptionRegistry.java   # replace with trie-backed impl
│   │   └── ValueWithTopicFilter.java          # copy TBMQ data class
│   └── mqtt/
│       └── retain/
│           ├── RetainMsgTrie.java              # interface (copy TBMQ)
│           ├── ConcurrentMapRetainMsgTrie.java # impl (copy TBMQ, strip StatsManager)
│           ├── RetainedMsgService.java         # existing interface — add getRetainedMessages(filter)
│           └── DefaultRetainedMsgService.java  # replace with trie-backed impl
└── actors/
    └── client/
        └── msg/
            └── DeliverMsg.java                # already exists — no changes needed
```

### Pattern 1: Dispatch Queue Submit (Publisher Side)

The publisher's actor (inside `ClientActor.processPublish()`) no longer calls `deliverToSubscribers()` directly. Instead it calls `MsgDispatcherService.dispatch(PublishMsg)`. The service submits to the queue and returns immediately. QoS acks (PUBACK/PUBREC) are sent before or independently of dispatch.

```java
// Source: adapted from TBMQ MsgDispatcherServiceImpl pattern
// DefaultMsgDispatcherService.java
public void dispatch(PublishMsg msg) {
    boolean offered = queue.offer(msg);
    if (!offered) {
        droppedMsgsCounter.increment();
        log.debug("[{}] Dispatch queue full — message dropped", msg.getTopicName());
    }
}
```

### Pattern 2: Dispatch Consumer (Background Thread)

A fixed thread pool reads from the queue and performs trie lookup + actor delivery:

```java
// DefaultMsgDispatcherService.java — started in @PostConstruct
private void startConsumers() {
    for (int i = 0; i < consumerThreads; i++) {
        consumerPool.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    PublishMsg msg = queue.take(); // blocks until available
                    deliverToSubscribers(msg);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
}

private void deliverToSubscribers(PublishMsg msg) {
    List<ValueWithTopicFilter<Subscription>> matches =
        subscriptionRegistry.getSubscriptions(msg.getTopicName()); // trie lookup
    for (ValueWithTopicFilter<Subscription> match : matches) {
        Subscription sub = match.getValue();
        if (sub.getSessionCtx().getState() != SessionState.CONNECTED) {
            continue;
        }
        int deliveryQos = Math.min(msg.getQos(), sub.getQos());
        // Send DeliverMsg to subscriber's actor mailbox (non-blocking)
        TbActorRef subscriberActor = actorSystem.getActor(new TbTypeActorId("client", sub.getClientId()));
        if (subscriberActor != null) {
            subscriberActor.tell(new DeliverMsg(msg, deliveryQos));
        }
    }
}
```

### Pattern 3: Subscriber Actor Handles DeliverMsg

`ClientActor` already has `DELIVER_MSG` in `MsgType` and `DeliverMsg` message class. Add a handler in `process()`:

```java
case DELIVER_MSG -> processDeliver((DeliverMsg) msg);
```

The delivery logic (packet ID allocation, outbound map tracking, `channel.writeAndFlush()`) moves from `deliverToSubscribers()` into `processDeliver()`. Since this runs inside the subscriber's actor mailbox, thread safety is maintained.

### Pattern 4: Updated SubscriptionRegistry Interface

The existing `getSubscriptions(String topicName)` returns `Set<Subscription>`. Phase 3 needs it to return `List<ValueWithTopicFilter<Subscription>>` to carry the matched topic filter for QoS downgrade. Update the interface signature:

```java
// SubscriptionRegistry.java — updated method
List<ValueWithTopicFilter<Subscription>> getSubscriptions(String topicName);
```

`DefaultSubscriptionRegistry` (now backed by the trie) delegates to `ConcurrentMapSubscriptionTrie.get(topicName)`. The `ClientActor.deliverToSubscribers()` call site must also be updated.

### Pattern 5: RetainedMsg Wildcard Delivery on Subscribe

`RetainedMsgService` adds a new method for wildcard-aware lookup:

```java
// RetainedMsgService.java — new method
List<RetainedMsg> getRetainedMessages(String topicFilter);
```

`DefaultRetainedMsgService` uses `ConcurrentMapRetainMsgTrie.get(topicFilter)` which handles wildcards. The trie's `get()` traversal interprets the filter (may contain `+` or `#`) and collects all matching stored topics.

On subscribe, `ClientActor.processSubscribe()` replaces the `retainedMsgService.getRetainedMessage(topicFilter)` call (exact-match `Optional`) with `retainedMsgService.getRetainedMessages(topicFilter)` (wildcard `List`), then iterates and delivers each result.

### Pattern 6: Copy Strategy for Trie Classes

Both trie classes depend on `StatsManager` (for `AtomicInteger`/`AtomicLong` counters) and `@Value`-injected `waitForClearLockMs`. The lightweight module has no `StatsManager`. Resolution:

- Replace `StatsManager.createSubscriptionSizeCounter()` with `new AtomicInteger(0)` directly
- Replace `StatsManager.createSubscriptionTrieNodesCounter()` with `new AtomicLong(0)` directly
- Replace `@Value("${mqtt.subscription-trie.wait-for-clear-lock-ms}")` with a hardcoded default (e.g. `5000` ms) or a `@ConfigurationProperties` field
- Keep the full trie algorithm intact (stack-based DFS, `ConcurrentHashMap` nodes, `ReadWriteLock`)

The `ConcurrentMapRetainMsgTrie` has the same pattern — same resolution.

### Key Difference Between the Two Tries

| Aspect | `ConcurrentMapSubscriptionTrie` | `ConcurrentMapRetainMsgTrie` |
|--------|--------------------------------|------------------------------|
| Node values | `Set<T>` (multiple subscriptions per node) | `AtomicReference<T>` (one retained msg per topic) |
| `get()` direction | topic string → find all matching filters | topic filter (may have wildcards) → find all matching topics |
| `put()` semantics | addOrReplace in Set | getAndSet on AtomicReference |
| Delete | Predicate-filtered `removeAll` | Set to null via `getAndSet(null)` |

This is the key insight for D-08/D-09: the subscription trie does filter-to-topic matching (given a publish topic, find all subscription filters that match it). The retained message trie does the inverse: given a subscribe filter (possibly with wildcards), find all stored topics that match it. Both use DFS stack traversal but interpret wildcards differently. TBMQ correctly separates these into two distinct trie classes.

### Anti-Patterns to Avoid

- **Blocking the actor thread:** Never call `queue.take()` or `queue.put()` (blocking) from within an actor's `process()` method. Use `queue.offer()` (non-blocking) and drop on full.
- **Inline delivery from the publisher's actor:** The Phase 2 `deliverToSubscribers()` iterates all subscribers synchronously inside the publisher's actor. Under high fan-out (1 publisher, 10k subscribers) this blocks the publisher's actor for O(N) time. Phase 3 replaces this with a single `queue.offer()` call.
- **Writing directly to subscriber channel from dispatch thread:** Netty channels are not thread-safe for writes from arbitrary threads. Always send a `DeliverMsg` to the subscriber's actor mailbox; the actor's executor handles the write.
- **Sharing `DeliverMsg` instances across subscribers:** The `PublishMsg` payload byte array is shared (read-only), but per-subscriber state (deliveryQos, packetId) must be per-delivery. `DeliverMsg` is already designed correctly (final fields, per-invocation construction).

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Wildcard topic matching | Custom regex or string-split loop | `ConcurrentMapSubscriptionTrie` (copy from TBMQ) | MQTT wildcard rules (`$SYS/` exclusion, `#` at end only, `+` single-level) have many edge cases; TBMQ's trie has been tested in production |
| Retained message wildcard lookup | Linear scan of HashMap | `ConcurrentMapRetainMsgTrie` (copy from TBMQ) | Same wildcard edge cases; trie traversal is O(topic-depth) not O(N-messages) |
| Bounded queue backpressure | Custom ring buffer | `LinkedBlockingQueue(capacity)` | Built-in, correct, zero new dependencies; upgrade path to Disruptor is trivial behind the factory interface |

**Key insight:** The trie traversal correctness (especially `$SYS/` exclusion and the `notStartingWith$()` check) is non-trivial. TBMQ has it right — copy, don't rewrite.

---

## Common Pitfalls

### Pitfall 1: Blocking Publisher Actor on Queue Full
**What goes wrong:** Using `queue.put()` (blocking) instead of `queue.offer()` causes the publisher's actor thread to stall when the queue is full, backing up all other messages for that client.
**Why it happens:** Confusing the "ensure delivery" concern with the "don't block" constraint. Per D-04, delivery is not guaranteed when queue is full — drop and count.
**How to avoid:** Always use `queue.offer(msg)` and check the boolean return. Increment `droppedMsgsCounter` on false.
**Warning signs:** Publisher actors stop processing; Netty I/O threads idle while actor queue fills.

### Pitfall 2: Delivering from Dispatch Thread to Netty Channel Directly
**What goes wrong:** Dispatch consumer thread calls `subscriberCtx.getChannel().writeAndFlush()` directly. Netty EventLoop executes writes, but submitting from outside the EventLoop is only safe for `writeAndFlush()` — however QoS 1/2 state updates (packet ID allocation, outbound map) are NOT thread-safe without synchronization.
**Why it happens:** The Phase 2 delivery was inside the subscriber's actor (safe). Moving delivery to a shared dispatch thread breaks actor isolation.
**How to avoid:** Dispatch thread sends `DeliverMsg` to subscriber's actor mailbox via `actorRef.tell()`. All channel writes and state updates happen inside the actor.
**Warning signs:** Race conditions in outbound QoS maps; duplicate packet IDs.

### Pitfall 3: Looking Up Actor by clientId Before Actor Is Created
**What goes wrong:** Dispatch consumer calls `actorSystem.getActor(clientActorId)` but the actor was destroyed on disconnect and not yet recreated for the new session.
**Why it happens:** Race between disconnect cleanup and message delivery for messages in-flight during takeover.
**How to avoid:** Null-check the actor ref before `tell()`. A missed delivery is acceptable (subscriber disconnected mid-flow). Do not throw or log at WARN for null actor — use `log.trace`.
**Warning signs:** NullPointerExceptions in dispatch consumer threads; cascade failures.

### Pitfall 4: Interface Signature Mismatch Between Trie and Registry
**What goes wrong:** `SubscriptionRegistry.getSubscriptions()` currently returns `Set<Subscription>`. After adding the trie the return type becomes `List<ValueWithTopicFilter<Subscription>>`. All call sites in `ClientActor` must be updated simultaneously.
**Why it happens:** Interface update not propagated to all callers.
**How to avoid:** Update the interface, find all compilation errors, fix in the same commit. There is exactly one call site in `ClientActor.deliverToSubscribers()` and test code.
**Warning signs:** Compilation failures; tests that pass exact-match assertions but silently skip wildcard delivery.

### Pitfall 5: Retained Trie $SYS/ Exclusion
**What goes wrong:** `ConcurrentMapRetainMsgTrie.get()` checks `notStartingWith$()` using `childNode.key`. The `key` field on `Node` is set in `computeIfAbsent` during `put()`. If `key` is null for the root's direct children (bug in copy), `$SYS/` topics will not be excluded.
**Why it happens:** The `Node(String key)` constructor is used for non-root nodes; root uses `Node()` (no-arg). The check reads `childNode.key.isEmpty() || childNode.key.charAt(0) != '$'` — if `key` is null, NPE.
**How to avoid:** Verify the copy preserves the `Node(String key)` constructor and that `computeIfAbsent` uses it. Test with a `$SYS/broker/uptime` topic and a `#` wildcard subscriber — should NOT deliver.
**Warning signs:** `$SYS/` messages delivered to wildcard subscribers.

### Pitfall 6: ClearEmptyNodes Without Lock Timeout Config
**What goes wrong:** `clearEmptyNodes()` uses `lock.writeLock().tryLock(waitForClearLockMs, ...)`. In the copy, `waitForClearLockMs` may default to 0 if the `@Value` annotation is removed without a replacement, causing immediate lock failure and skipped cleanup.
**Why it happens:** Stripping `@Value` without providing a default.
**How to avoid:** Hardcode a sensible default (e.g., `5000`) or add a `@ConfigurationProperties` property. Log a clear error when `clearEmptyNodes()` fails to acquire the lock.
**Warning signs:** Trie nodes accumulate unboundedly after unsubscribe; memory growth over time.

### Pitfall 7: DeliverMsg Delivery QoS Downgrade in Wrong Layer
**What goes wrong:** QoS downgrade (`min(publishQos, subscriptionQos)`) computed in the dispatch consumer using the match from the trie. But the matched `ValueWithTopicFilter.getTopicFilter()` is the subscription filter, and `match.getValue().getQos()` is the subscription QoS — this is correct. The pitfall is accidentally using the subscription filter QoS from the trie node instead of the `Subscription` object's QoS field.
**Why it happens:** `ValueWithTopicFilter<Subscription>` wraps a `Subscription` and a `topicFilter` string. It's easy to confuse `match.getTopicFilter()` (a String) with QoS.
**How to avoid:** Explicitly use `match.getValue().getQos()` for the subscription QoS. Document in code.
**Warning signs:** Messages delivered at wrong QoS; QoS 0 messages getting packet IDs.

---

## Code Examples

### Subscription Trie Key Operations

```java
// Source: TBMQ ConcurrentMapSubscriptionTrie.java (copied verbatim)
// get() traversal — stack-based DFS, no recursion
// The $SYS/ exclusion is at line 88:
private boolean notStartingWith$(String topic, TopicPosition<T> topicPosition) {
    return topicPosition.segmentStartIndex != 0 || topic.charAt(0) != '$';
}
// Called BEFORE checking wildcard nodes — ensures $SYS/+ and $SYS/# never match
```

### Retained Message Trie Key Operations

```java
// Source: TBMQ ConcurrentMapRetainMsgTrie.java (copied verbatim)
// get() takes a topicFilter (possibly with wildcards), returns List<T> of matching retained msgs
// Node has: AtomicReference<T> value (one msg per exact topic), ConcurrentMap<String,Node> children
// $SYS/ check uses childNode.key (the segment string set at put() time)
private boolean notStartingWith$(TopicPosition<T> topicPosition, Node<T> childNode) {
    return topicPosition.segmentStartIndex != 0 || childNode.key.isEmpty() || childNode.key.charAt(0) != '$';
}
```

### Queue Factory Interface (D-04 implementation)

```java
// PublishMsgQueueFactory.java
public interface PublishMsgQueueFactory {
    BlockingQueue<PublishMsg> createQueue();
}

// LinkedBlockingQueueFactory.java
@Configuration
@ConfigurationProperties(prefix = "tbmq.dispatch")
@Data
public class LinkedBlockingQueueFactory implements PublishMsgQueueFactory {
    private int queueCapacity = 100_000; // TBMQ_DISPATCH_QUEUE_CAPACITY

    @Override
    public BlockingQueue<PublishMsg> createQueue() {
        return new LinkedBlockingQueue<>(queueCapacity);
    }
}
```

### Consumer Thread Pool Recommendation

```java
// DefaultMsgDispatcherService.java @PostConstruct
// Claude's discretion (D-10): 2 consumer threads is the recommendation.
// Rationale:
//   - channel.writeAndFlush() is non-blocking (Netty handles async write)
//   - Subscription trie lookup is O(topic-depth) ~microseconds
//   - tell() on actor mailbox is a ConcurrentLinkedQueue.offer() — nanoseconds
//   - 2 threads provides parallelism for burst load without over-subscribing CPU
//   - At 50,000 msg/sec with avg 3 subscribers = 150,000 deliveries/sec
//   - Each delivery: trie lookup + N actor tells = ~10µs total
//   - 2 threads × 100,000 ops/sec capacity each = 200,000 ops/sec headroom
private static final int DEFAULT_CONSUMER_THREADS = 2;
```

### DeliverMsg Handler in ClientActor

```java
// ClientActor.java — add case to process() switch
case DELIVER_MSG -> processDeliver((DeliverMsg) msg);

private void processDeliver(DeliverMsg msg) {
    // Guard: actor may receive DELIVER_MSG after disconnect (actor not yet stopped)
    if (sessionCtx == null || sessionCtx.getState() != SessionState.CONNECTED) {
        return;
    }
    PublishMsg publishMsg = msg.getPublishMsg();
    int deliveryQos = msg.getDeliveryQos();
    // Existing QoS delivery logic moved here from deliverToSubscribers()
    if (deliveryQos == 0) {
        sessionCtx.getChannel().writeAndFlush(
            messageGenerator.createPublish(publishMsg.getTopicName(), 0,
                publishMsg.getPayload(), publishMsg.isRetain(), false, 0));
    } else if (deliveryQos == 1) {
        int pktId = sessionCtx.getPacketIdAllocator().nextPacketId();
        sessionCtx.getOutboundQos1().put(pktId, PublishMsg.builder()...build());
        sessionCtx.getChannel().writeAndFlush(...);
    } else {
        int pktId = sessionCtx.getPacketIdAllocator().nextPacketId();
        sessionCtx.getOutboundQos2().put(pktId, PublishMsg.builder()...build());
        sessionCtx.getChannel().writeAndFlush(...);
    }
}
```

### LWT Dispatch Migration

The LWT delivery path in `processDisconnect()` also calls `deliverToSubscribers()`. This call must also migrate to `msgDispatcherService.dispatch(PublishMsg)` — the LWT message is just another publish from the dispatcher's perspective.

---

## State of the Art

| Old Approach (Phase 2) | Current Approach (Phase 3) | Impact |
|------------------------|---------------------------|--------|
| Inline delivery in publisher's actor | Queue-based dispatch via `MsgDispatcherService` | Publisher never blocked by slow subscribers |
| Exact-match `ConcurrentHashMap` registry | `ConcurrentMapSubscriptionTrie` | `+` and `#` wildcards correctly matched |
| `Optional<RetainedMsg>` exact-match on subscribe | `List<RetainedMsg>` trie lookup on subscribe | Wildcard subscribe delivers all matching retained messages |
| `deliverToSubscribers()` writes to channel directly | `DeliverMsg` actor message | Thread safety maintained; QoS state managed by subscriber's actor |

---

## Open Questions

1. **Actor lookup in dispatch consumer**
   - What we know: `DefaultTbActorSystem` has a `ConcurrentMap<TbActorId, TbActorMailbox> actors` field.
   - What's unclear: Is there a public `getActor(TbActorId)` or `tell(TbActorId, msg)` method on `TbActorSystem` for external callers?
   - Recommendation: Check `TbActorSystem` interface. If no `tell(TbActorId, msg)`, the `MsgDispatcherService` may need to inject `TbActorSystem` and use `tell()`, OR the `Subscription` object should store a `TbActorRef` instead of `ClientSessionCtx`. The current `Subscription` stores `ClientSessionCtx` — simplest path is to use `sessionCtx.getChannel()` for QoS 0, and send via actor mailbox for QoS 1/2.

2. **QoS ack timing relative to dispatch**
   - What we know: Per D-04, publisher receives PUBACK/PUBREC before subscriber delivery completes. This is correct per MQTT spec.
   - What's unclear: The current code sends PUBACK then calls `deliverToSubscribers()` synchronously. After Phase 3, PUBACK is sent then `dispatch()` submits to queue. No change needed — just verify the ordering is preserved in `processPublish()`.
   - Recommendation: Add a comment in `processPublish()` noting the intentional ordering: QoS ack first, then dispatch.

3. **Default queue capacity**
   - What we know: TBMQ uses much larger queues (Kafka topic-level). For lightweight single-node at 50k msg/sec target (from STATE.md), a 100,000 capacity queue provides 2 seconds of burst buffering.
   - Recommendation: Default to 100,000 (`TBMQ_DISPATCH_QUEUE_CAPACITY` env var). At ~100 bytes average message size, this is ~10 MB of peak memory.

---

## Environment Availability

Step 2.6: No new external dependencies. All tools available.

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 17 | Build and runtime | Yes | OpenJDK 17.0.18 | — |
| Maven 3.9 | Build | Yes | 3.9.2 | — |
| JUnit 5 (via Spring Boot BOM) | Tests | Yes | Via existing test infra | — |
| Eclipse Paho MQTT Client | Integration tests | Yes | 1.2.5 (existing) | — |

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test |
| Config file | `lightweight/src/test/java/.../mqtt/AbstractMqttIntegrationTest.java` |
| Quick run command | `mvn -pl lightweight test -Dtest="ConcurrentMapSubscriptionTrieTest,MsgDispatcherServiceTest" -q` |
| Full suite command | `mvn -pl lightweight test -q` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| INFR-01 | Wildcard `+` publish routes to matching subscriber | integration | `mvn -pl lightweight test -Dtest="MqttSubscribeIntegrationTest#testSubscribe_wildcardPlus_deliversMessages" -q` | Phase 2 test exists; change `@Disabled` assertion from 0 to 1 |
| INFR-01 | Wildcard `#` publish routes to matching subscriber | integration | `mvn -pl lightweight test -Dtest="MqttSubscribeIntegrationTest#testSubscribe_wildcardHash_deliversMessages" -q` | Phase 2 test exists; update assertion |
| INFR-01 | Wildcard retained message delivered on `+` subscribe | integration | `mvn -pl lightweight test -Dtest="MqttRetainedMsgIntegrationTest#testRetainedMsg_wildcardSubscribe_deliversAll" -q` | Wave 0 gap |
| INFR-01 | `$SYS/` topic not matched by `#` wildcard | integration | `mvn -pl lightweight test -Dtest="MqttSubscribeIntegrationTest#testSubscribe_hashWildcard_doesNotMatchSysTopics" -q` | Wave 0 gap |
| INFR-01 | Dispatch queue full — messages dropped, counter incremented | unit | `mvn -pl lightweight test -Dtest="DefaultMsgDispatcherServiceTest#testDispatch_queueFull_dropsAndCountsMessage" -q` | Wave 0 gap |
| INFR-04 | `LinkedBlockingQueueFactory` creates bounded queue | unit | `mvn -pl lightweight test -Dtest="LinkedBlockingQueueFactoryTest" -q` | Wave 0 gap |
| INFR-04 | Swap factory bean — consumers unaffected | unit | `mvn -pl lightweight test -Dtest="PublishMsgQueueFactoryTest" -q` | Wave 0 gap |

### Sampling Rate
- **Per task commit:** `mvn -pl lightweight test -Dtest="ConcurrentMapSubscriptionTrieTest,DefaultMsgDispatcherServiceTest" -q`
- **Per wave merge:** `mvn -pl lightweight test -q`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `lightweight/src/test/java/.../service/subscription/ConcurrentMapSubscriptionTrieTest.java` — unit tests for trie: exact match, `+` wildcard, `#` wildcard, `$SYS/` exclusion, concurrent put/delete, clearEmptyNodes
- [ ] `lightweight/src/test/java/.../service/dispatch/DefaultMsgDispatcherServiceTest.java` — unit tests: dispatch enqueues, queue full drops and counts, consumer delivers via actor
- [ ] `lightweight/src/test/java/.../service/dispatch/LinkedBlockingQueueFactoryTest.java` — queue capacity respected
- [ ] `lightweight/src/test/java/.../mqtt/MqttRetainedMsgIntegrationTest.java` — add `testRetainedMsg_wildcardSubscribe_deliversAll()` (new test method in existing class)
- [ ] `lightweight/src/test/java/.../mqtt/MqttSubscribeIntegrationTest.java` — update existing wildcard tests from "assert 0 delivery" to "assert 1 delivery", add `$SYS/` exclusion test

*(Existing `AbstractMqttIntegrationTest` infrastructure covers all integration tests — no new base class needed.)*

---

## Sources

### Primary (HIGH confidence)
- TBMQ source: `application/src/main/java/.../service/subscription/ConcurrentMapSubscriptionTrie.java` — full algorithm read
- TBMQ source: `application/src/main/java/.../service/mqtt/retain/ConcurrentMapRetainMsgTrie.java` — full algorithm read
- TBMQ source: `application/src/main/java/.../service/processing/MsgDispatcherServiceImpl.java` — dispatch pattern read
- TBMQ source: `application/src/main/java/.../service/processing/downlink/DownLinkProxyImpl.java` — local/remote routing read
- TBMQ source: `application/src/main/java/.../service/processing/downlink/basic/BasicDownLinkProcessorImpl.java` — delivery pattern read
- Lightweight source: `lightweight/src/main/java/.../actors/client/ClientActor.java` — full existing delivery path read
- Lightweight source: `lightweight/src/main/java/.../service/subscription/DefaultSubscriptionRegistry.java` — current impl read
- Lightweight source: `lightweight/src/main/java/.../service/mqtt/retain/DefaultRetainedMsgService.java` — current impl read
- `.planning/phases/03-message-dispatch/03-CONTEXT.md` — locked decisions read
- `.planning/REQUIREMENTS.md` — INFR-01, INFR-04 read
- `.planning/STATE.md` — accumulated decisions read

### Secondary (MEDIUM confidence)
- JDK 17 `LinkedBlockingQueue` JavaDoc — bounded queue semantics, `offer()` vs `put()` behavior (well-known JDK behavior)
- MQTT 3.1.1 spec section 4.7.2 (`$SYS/` wildcard exclusion rule) — verified via TBMQ implementation

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all existing project libraries, no new dependencies
- Architecture: HIGH — canonical TBMQ source files read; copy-and-adapt strategy fully mapped
- Pitfalls: HIGH — identified from reading actual trie code and actor delivery patterns
- Thread model: HIGH — Netty + actor-per-client model is established from Phase 1/2

**Research date:** 2026-04-08
**Valid until:** 2026-05-08 (stable domain — JDK stdlib + copied TBMQ code)
