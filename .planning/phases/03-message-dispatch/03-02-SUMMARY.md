---
phase: 03-message-dispatch
plan: 02
subsystem: messaging
tags: [dispatch, queue, trie, subscription, retained-messages, wildcard, mqtt, concurrency, smartlifecycle]

# Dependency graph
requires:
  - phase: 03-message-dispatch
    plan: 01
    provides: "ConcurrentMapSubscriptionTrie, ConcurrentMapRetainMsgTrie"
provides:
  - MsgDispatcherService: non-blocking queue-backed dispatch interface
  - DefaultMsgDispatcherService: SmartLifecycle consumer thread pool with dropped_msgs counter
  - PublishMsgQueueFactory: swappable queue factory interface (INFR-04)
  - LinkedBlockingQueueFactory: default LinkedBlockingQueue implementation (100k capacity)
  - DispatchConfiguration: Spring @Bean factories for subscription and retain msg tries
  - DefaultSubscriptionRegistry: trie-backed wildcard subscription matching
  - DefaultRetainedMsgService: trie-backed wildcard retained message lookup
  - ClientActor: dispatch-driven delivery with processDeliver() + QoS state management
affects:
  - 03-03: integration tests validate end-to-end wildcard delivery, retained message wildcard, dispatch pipeline

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "SmartLifecycle for dispatcher start/stop — integrates with Spring Boot graceful shutdown"
    - "queue.offer() not queue.put() for non-blocking dispatch — never block caller thread"
    - "Per-client topic filter index (ConcurrentHashMap<String, Set<String>>) for O(n_topics) removeAllSubscriptions"
    - "Daemon threads for dispatch consumer pool — no JVM shutdown delay"

key-files:
  created:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/MsgDispatcherService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/PublishMsgQueueFactory.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/LinkedBlockingQueueFactory.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/DispatchConfiguration.java
  modified:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/SubscriptionRegistry.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/DefaultSubscriptionRegistry.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/Subscription.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/ConcurrentMapSubscriptionTrie.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/RetainedMsgService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/DefaultRetainedMsgService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/ConcurrentMapRetainMsgTrie.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActorCreator.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttChannelInitializer.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsService.java
    - lightweight/src/main/resources/tbmq-lightweight.yml
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttSubscribeIntegrationTest.java

key-decisions:
  - "queue.offer() enforced over queue.put() — non-blocking dispatch per D-04; drop and count when full"
  - "Daemon threads for dispatch consumers — JVM exits cleanly without waiting for thread pool drain"
  - "@EqualsAndHashCode(of = clientId) on Subscription — trie addOrReplace requires clientId-only equality for correct re-subscribe QoS update semantics"
  - "Per-client topic filter index in DefaultSubscriptionRegistry — avoids full trie scan on disconnect"
  - "trie @Value prefix updated from mqtt.* to tbmq.* — consistent with all other lightweight config properties"

requirements-completed: [INFR-01, INFR-04]

# Metrics
duration: 9min
completed: 2026-04-08
---

# Phase 03 Plan 02: Dispatch Service Wire-up Summary

**Queue-backed dispatch pipeline wired: publisher actors enqueue via MsgDispatcherService, consumer threads match wildcards via trie, deliver via actor mailbox — inline deliverToSubscribers() removed from ClientActor**

## Performance

- **Duration:** 9 min
- **Started:** 2026-04-08T16:10:59Z
- **Completed:** 2026-04-08T16:20:02Z
- **Tasks:** 2
- **Files modified:** 14 (5 new, 9 modified + 1 test updated)

## Accomplishments

- Created 5 new files: MsgDispatcherService, DefaultMsgDispatcherService, PublishMsgQueueFactory, LinkedBlockingQueueFactory, DispatchConfiguration
- Refactored 9 existing files: SubscriptionRegistry, DefaultSubscriptionRegistry, Subscription, RetainedMsgService, DefaultRetainedMsgService, ClientActor, ClientActorCreator, MqttChannelInitializer, MqttSessionHandler
- Removed inline `deliverToSubscribers()` from ClientActor — all publishes now route through bounded queue
- Added `processDeliver(DeliverMsg)` to ClientActor for QoS 0/1/2 delivery with packet ID allocation
- Wildcard subscription delivery now works via trie (+ and # patterns)
- Wildcard retained message delivery on subscribe now works via RetainMsgTrie

## Task Commits

Each task was committed atomically:

1. **Task 1: Create dispatch service, queue factory, and configuration** - `21c943182` (feat)
2. **Task 2: Refactor ClientActor, SubscriptionRegistry, and RetainedMsgService for trie-backed dispatch** - `a92b471f3` (feat)

## Files Created/Modified

**New files:**
- `MsgDispatcherService.java` — Non-blocking dispatch interface per D-01
- `DefaultMsgDispatcherService.java` — SmartLifecycle consumer pool, queue.offer(), dropped_msgs counter
- `PublishMsgQueueFactory.java` — Swappable queue factory interface per INFR-04
- `LinkedBlockingQueueFactory.java` — Default 100k capacity LinkedBlockingQueue
- `DispatchConfiguration.java` — @Bean factory for subscription and retain msg tries

**Modified files:**
- `SubscriptionRegistry.java` — getSubscriptions() return changed from Set<> to List<ValueWithTopicFilter<>>
- `DefaultSubscriptionRegistry.java` — Backed by ConcurrentMapSubscriptionTrie, per-client filter index for O(n) removeAll
- `Subscription.java` — @EqualsAndHashCode(of="clientId") for correct trie re-subscribe semantics
- `RetainedMsgService.java` — Added getRetainedMessages(topicFilter) for wildcard delivery
- `DefaultRetainedMsgService.java` — Backed by ConcurrentMapRetainMsgTrie
- `ClientActor.java` — deliverToSubscribers() removed; processDeliver() added; all publishes/LWT route through dispatch
- `ConcurrentMapSubscriptionTrie.java` — @Value prefix updated mqtt.* -> tbmq.*
- `ConcurrentMapRetainMsgTrie.java` — @Value prefix updated mqtt.* -> tbmq.*
- `tbmq-lightweight.yml` — dispatch, subscription-trie, retain-msg-trie config sections added
- `MqttSubscribeIntegrationTest.java` — Wildcard tests updated to assert delivery (Phase 3 activated)

## Decisions Made

- `queue.offer()` not `queue.put()` — non-blocking dispatch per D-04; calling thread is never blocked
- Daemon threads for consumer pool — no JVM shutdown delay; ShutdownNow() + awaitTermination(5s) for clean stop
- `@EqualsAndHashCode(of = "clientId")` on Subscription — trie addOrReplace needs clientId-only equality to correctly update QoS on re-subscribe
- Per-client topic filter index (`ConcurrentHashMap<String, Set<String>>`) — avoids O(trie_size) full scan on disconnect; each removeAllSubscriptions is O(client_subscriptions)
- trie `@Value` property prefix changed from `mqtt.*` to `tbmq.*` — keeps all lightweight config under the `tbmq:` YAML root; no more mixed prefixes

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Updated stale wildcard integration tests asserting Phase 2 behavior**
- **Found during:** Task 2 (test run after implementation)
- **Issue:** `testSubscribe_wildcardPlus_storedButNoDelivery` and `testSubscribe_wildcardHash_storedButNoDelivery` asserted `count.get() == 0` (no delivery). These tests were written in Phase 2 when delivery was exact-match only. Phase 3 enables wildcard delivery, so these tests now fail because messages ARE correctly delivered.
- **Fix:** Renamed tests to `testSubscribe_wildcardPlus_deliversMessages` and `testSubscribe_wildcardHash_deliversMessages`; updated assertions to await delivery and assert `count.get() == 1`
- **Files modified:** `MqttSubscribeIntegrationTest.java`
- **Verification:** All 75 tests pass (3 pre-existing @Disabled skipped)
- **Committed in:** `a92b471f3` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - Bug)
**Impact on plan:** Test correctness fix — Phase 3 intentionally enables wildcard delivery; stale Phase 2 assertions were simply wrong in the new context.

## Issues Encountered

- Task 1 and Task 2 had a circular compile dependency (DefaultMsgDispatcherService requires `subscriptionRegistry.getSubscriptions()` with the new List return type, which is defined in Task 2's SubscriptionRegistry update). Both tasks were implemented in the same session and committed atomically per task after all changes compiled.

## Next Phase Readiness

- Dispatch pipeline is fully wired: publish -> queue -> trie lookup -> actor mailbox
- All 75 tests pass including wildcard delivery tests
- Plan 03 can write end-to-end integration tests covering multi-subscriber wildcard delivery, retained message wildcard, and QoS downgrade in the dispatch path

## Self-Check: PASSED

All 5 new files and 9 modified files verified. Both commits exist in git log.
