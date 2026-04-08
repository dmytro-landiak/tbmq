---
phase: 3
slug: message-dispatch
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-08
---

# Phase 3 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test |
| **Config file** | `lightweight/src/test/java/.../mqtt/AbstractMqttIntegrationTest.java` |
| **Quick run command** | `mvn -pl lightweight test -Dtest="ConcurrentMapSubscriptionTrieTest,DefaultMsgDispatcherServiceTest" -q` |
| **Full suite command** | `mvn -pl lightweight test -q` |
| **Estimated runtime** | ~30 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn -pl lightweight test -Dtest="ConcurrentMapSubscriptionTrieTest,DefaultMsgDispatcherServiceTest" -q`
- **After every plan wave:** Run `mvn -pl lightweight test -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 03-01-01 | 01 | 1 | INFR-01 | integration | `mvn -pl lightweight test -Dtest="MqttSubscribeIntegrationTest#testSubscribe_wildcardPlus_deliversMessages" -q` | Phase 2 test exists; update assertion | ⬜ pending |
| 03-01-02 | 01 | 1 | INFR-01 | integration | `mvn -pl lightweight test -Dtest="MqttSubscribeIntegrationTest#testSubscribe_wildcardHash_deliversMessages" -q` | Phase 2 test exists; update assertion | ⬜ pending |
| 03-01-03 | 01 | 1 | INFR-01 | integration | `mvn -pl lightweight test -Dtest="MqttRetainedMsgIntegrationTest#testRetainedMsg_wildcardSubscribe_deliversAll" -q` | ❌ Wave 0 gap | ⬜ pending |
| 03-01-04 | 01 | 1 | INFR-01 | integration | `mvn -pl lightweight test -Dtest="MqttSubscribeIntegrationTest#testSubscribe_hashWildcard_doesNotMatchSysTopics" -q` | ❌ Wave 0 gap | ⬜ pending |
| 03-02-01 | 02 | 1 | INFR-01 | unit | `mvn -pl lightweight test -Dtest="DefaultMsgDispatcherServiceTest#testDispatch_queueFull_dropsAndCountsMessage" -q` | ❌ Wave 0 gap | ⬜ pending |
| 03-02-02 | 02 | 1 | INFR-04 | unit | `mvn -pl lightweight test -Dtest="LinkedBlockingQueueFactoryTest" -q` | ❌ Wave 0 gap | ⬜ pending |
| 03-02-03 | 02 | 1 | INFR-04 | unit | `mvn -pl lightweight test -Dtest="PublishMsgQueueFactoryTest" -q` | ❌ Wave 0 gap | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `lightweight/src/test/java/.../service/subscription/ConcurrentMapSubscriptionTrieTest.java` — unit tests for trie: exact match, `+` wildcard, `#` wildcard, `$SYS/` exclusion, concurrent put/delete, clearEmptyNodes
- [ ] `lightweight/src/test/java/.../service/dispatch/DefaultMsgDispatcherServiceTest.java` — unit tests: dispatch enqueues, queue full drops and counts, consumer delivers via actor
- [ ] `lightweight/src/test/java/.../service/dispatch/LinkedBlockingQueueFactoryTest.java` — queue capacity respected
- [ ] `lightweight/src/test/java/.../mqtt/MqttRetainedMsgIntegrationTest.java` — add `testRetainedMsg_wildcardSubscribe_deliversAll()` (new test method in existing class)
- [ ] `lightweight/src/test/java/.../mqtt/MqttSubscribeIntegrationTest.java` — update existing wildcard tests from "assert 0 delivery" to "assert 1 delivery", add `$SYS/` exclusion test

*Existing `AbstractMqttIntegrationTest` infrastructure covers all integration tests — no new base class needed.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Netty I/O threads remain unblocked under sustained publish load | INFR-01 | Requires spike load test with thread dump analysis | Run 50k msg/sec publish load for 60s, check Netty event loop threads via thread dump — no blocked/waiting states |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
