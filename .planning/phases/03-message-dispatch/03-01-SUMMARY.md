---
phase: 03-message-dispatch
plan: 01
subsystem: messaging
tags: [trie, subscription, retained-messages, wildcard, mqtt, concurrency]

# Dependency graph
requires:
  - phase: 02-core-protocol-mqtt-3-1-1
    provides: "DefaultSubscriptionRegistry, DefaultRetainedMsgService using exact-match HashMap lookup"
provides:
  - ConcurrentMapSubscriptionTrie: O(topic-depth) wildcard subscription matching with $SYS exclusion
  - ConcurrentMapRetainMsgTrie: wildcard retained message lookup with $SYS exclusion
  - BrokerConstants: MQTT wildcard and topic delimiter constants
  - ValueWithTopicFilter: typed wrapper for subscription trie results
  - SubscriptionTrie interface and RetainMsgTrie interface (no external exceptions)
affects:
  - 03-02: dispatcher service wire-up uses these tries as core data structures
  - 03-03: integration tests validate end-to-end wildcard delivery via tries

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Copy-adapt TBMQ upstream: copy class verbatim, remove external dependencies (StatsManager, Guava, custom exceptions), adapt package"
    - "No-arg constructor replaces @Autowired StatsManager: use AtomicInteger/AtomicLong directly"
    - "ConcurrentHashMap.newKeySet() replaces Guava Sets.newConcurrentHashSet()"
    - "throws Exception in interface replaces throws CustomTrieException — avoids importing TBMQ exception classes"

key-files:
  created:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/common/BrokerConstants.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/ValueWithTopicFilter.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/SubscriptionTrie.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/ConcurrentMapSubscriptionTrie.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/RetainMsgTrie.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/ConcurrentMapRetainMsgTrie.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/ConcurrentMapSubscriptionTrieTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/ConcurrentMapRetainMsgTrieTest.java
  modified: []

key-decisions:
  - "ConcurrentHashMap.newKeySet() used over Guava Sets.newConcurrentHashSet() — avoids Guava dependency in lightweight module"
  - "throws Exception in clearEmptyNodes() interface methods — avoids importing custom TBMQ exception hierarchy"
  - "notStartingWith$() in ConcurrentMapSubscriptionTrie guards against empty topic string to prevent StringIndexOutOfBoundsException"

patterns-established:
  - "Copy-adapt pattern: source from TBMQ, apply minimal lightweight adaptations (package, no Spring @Service, no StatsManager, no Guava)"
  - "Plain JUnit 5 tests: instantiate data structures directly via new ConcurrentMapXxx<>(), set @Value fields via Lombok @Setter"

requirements-completed: [INFR-01]

# Metrics
duration: 3min
completed: 2026-04-08
---

# Phase 03 Plan 01: Trie Data Structures Summary

**ConcurrentMapSubscriptionTrie and ConcurrentMapRetainMsgTrie copied from TBMQ with StatsManager/Guava removed, providing O(topic-depth) wildcard matching with $SYS/ exclusion and 24 passing unit tests**

## Performance

- **Duration:** 3 min
- **Started:** 2026-04-08T16:04:30Z
- **Completed:** 2026-04-08T16:07:45Z
- **Tasks:** 2
- **Files modified:** 8

## Accomplishments
- Copied and adapted 6 production trie source files from TBMQ upstream — no Guava, no StatsManager, no custom exception imports
- Created 24 unit tests (13 subscription trie + 11 retain msg trie) covering exact match, + wildcard, # wildcard, $SYS exclusion, delete, multi-subscriber, and size tracking
- Established the copy-adapt pattern for bringing TBMQ data structures into the lightweight module with minimal changes

## Task Commits

Each task was committed atomically:

1. **Task 1: Copy trie data structures from TBMQ with lightweight adaptations** - `860e5781e` (feat)
2. **Task 2: Unit tests for subscription trie and retained message trie** - `53de23847` (test)

**Plan metadata:** `d82f8bea4` (docs: complete plan)

_Note: Task 2 includes a Rule 1 auto-fix committed together with the test files_

## Files Created/Modified
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/common/BrokerConstants.java` - MQTT wildcard and topic delimiter constants
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/ValueWithTopicFilter.java` - Typed wrapper for trie results carrying the matched filter string
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/SubscriptionTrie.java` - Interface with clearEmptyNodes() throws Exception
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/ConcurrentMapSubscriptionTrie.java` - Wildcard subscription trie: stack-based DFS get(), recursive put(), $SYS exclusion, no Guava, no @Service
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/RetainMsgTrie.java` - Interface with clearEmptyNodes() throws Exception
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/ConcurrentMapRetainMsgTrie.java` - Wildcard retain msg trie: AtomicReference per node, Node(String key) for $SYS detection, no Guava, no @Service
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/ConcurrentMapSubscriptionTrieTest.java` - 13 JUnit 5 tests
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/retain/ConcurrentMapRetainMsgTrieTest.java` - 11 JUnit 5 tests

## Decisions Made
- Used `ConcurrentHashMap.newKeySet()` over Guava — avoids adding Guava as a dependency, JDK 8+ built-in is sufficient
- `throws Exception` in interface `clearEmptyNodes()` methods — avoids importing `SubscriptionTrieClearException` / `RetainMsgTrieClearException` from TBMQ; lightweight has no TBMQ exception hierarchy
- Empty-topic guard added to `notStartingWith$()` — the TBMQ upstream has this latent bug; lightweight fixes it proactively

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed StringIndexOutOfBoundsException in ConcurrentMapSubscriptionTrie for empty topic strings**
- **Found during:** Task 2 (Unit tests for subscription trie)
- **Issue:** `notStartingWith$(topic, topicPosition)` calls `topic.charAt(0)` without checking `topic.isEmpty()`. When test `testGet_emptyTopicNoNpe` called `trie.get("")`, the method threw `StringIndexOutOfBoundsException` instead of returning empty results
- **Fix:** Added `|| topic.isEmpty()` guard: `return topicPosition.segmentStartIndex != 0 || topic.isEmpty() || topic.charAt(0) != '$';`
- **Files modified:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/subscription/ConcurrentMapSubscriptionTrie.java`
- **Verification:** `testGet_emptyTopicNoNpe` passes; all 24 tests pass
- **Committed in:** `53de23847` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - Bug)
**Impact on plan:** Essential correctness fix. The behavior spec explicitly required "not NPE" for empty topics; this extends it to all string index errors. No scope creep.

## Issues Encountered
- Maven module not in parent reactor (`mvn -pl lightweight` fails from repo root) — ran Maven from `lightweight/` directory directly. Pre-existing limitation.

## User Setup Required
None — no external service configuration required.

## Next Phase Readiness
- Both tries compile and all tests pass
- Plan 02 can wire `ConcurrentMapSubscriptionTrie` and `ConcurrentMapRetainMsgTrie` as Spring beans and replace the Phase 2 exact-match `ConcurrentHashMap` in `DefaultSubscriptionRegistry` and `DefaultRetainedMsgService`
- No blockers

## Self-Check: PASSED

All 9 expected files found on disk. All 3 commits verified in git log.

---
*Phase: 03-message-dispatch*
*Completed: 2026-04-08*
