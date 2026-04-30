# Dispatcher Race Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate silent loss of messages dispatched during the `dispatch()` / `stop()` race window by draining the queue in `stop()` and accounting drained messages on the `mqtt.dispatch.dropped.total` meter.

**Architecture:** Single-method change to `DefaultMsgDispatcherService.stop()` — after `awaitTermination` returns, call `queue.drainTo(orphans)`, increment the dropped counter by `orphans.size()`, and log the count. The hot path (`dispatch()`) is unchanged. One new TDD-style test verifies the drain accounting.

**Tech Stack:** Java 17, JUnit 5, Mockito, AssertJ, Micrometer (`SimpleMeterRegistry`), Spring's `ReflectionTestUtils`. Build: Maven (`mvn -f lightweight/pom.xml`).

**Spec:** `docs/superpowers/specs/2026-04-30-dispatcher-race-window-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java` | Modify | Add orphan drain in `stop()`; remove now-meaningless `queue.size()` reference from the `awaitTermination` warn line. |
| `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherServiceTest.java` | Modify | Add `testStop_drainsOrphanedMessagesAndCountsAsDropped` covering the new drain logic. |

No new files. No new imports needed: `java.util.ArrayList` and `java.util.List` are already imported in the production class.

---

## Task 1: Drain Orphaned Queue Messages on Shutdown

**Files:**
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java:119-136`
- Test: `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherServiceTest.java`

### - [ ] Step 1: Write the failing test

Append a new test method just below `testStop_returnsQuicklyWhenQueueEmpty` in `DefaultMsgDispatcherServiceTest.java` (after line 151, before the `private PublishMsg buildMsg` helper at line 153). The test reuses the class-level `dispatcher`, `testQueue`, and `meterRegistry` initialised in `setUp()` — at that point consumers are dead but `running` is `true`, so `dispatch()` accepts messages straight into the queue with no consumer to drain them.

```java
    @Test
    void testStop_drainsOrphanedMessagesAndCountsAsDropped() {
        // Pre-conditions (from setUp): consumer pool is stopped, running=true, queue empty,
        // capacity=2. Dispatching here lands messages that no consumer will ever process —
        // that's exactly the silent-loss scenario the fix is meant to convert into a counted
        // loss.
        dispatcher.dispatch(buildMsg("orphan/1"));
        dispatcher.dispatch(buildMsg("orphan/2"));

        assertThat(testQueue).hasSize(2);
        assertThat(meterRegistry.counter("mqtt.dispatch.dropped.total").count())
                .as("dispatch() must not increment the dropped counter while offer() succeeds")
                .isEqualTo(0.0);

        // stop() must drain orphaned queue contents and account for them on the dropped meter.
        dispatcher.stop();

        assertThat(testQueue).isEmpty();
        assertThat(meterRegistry.counter("mqtt.dispatch.dropped.total").count())
                .as("stop() must count orphaned queue contents on the dropped meter")
                .isEqualTo(2.0);
    }
```

### - [ ] Step 2: Run the new test to verify it fails

Run:

```bash
mvn -f lightweight/pom.xml -o test -Dtest=DefaultMsgDispatcherServiceTest#testStop_drainsOrphanedMessagesAndCountsAsDropped
```

Expected: FAIL on the second counter assertion. Failure looks like:

```
AssertionFailedError: stop() must count orphaned queue contents on the dropped meter
expected: 2.0
 but was: 0.0
```

(The first assertion — counter = 0 after dispatch — passes. The queue-is-empty assertion will also fail because the unfixed `stop()` doesn't drain.)

If Maven complains it cannot run offline, drop `-o`.

### - [ ] Step 3: Implement the drain in `stop()`

Edit `DefaultMsgDispatcherService.java`. Replace the existing `stop()` method (lines 119–136) with:

```java
    @Override
    public void stop() {
        running = false;
        // Best-effort drain: stop accepting new work, give consumers time to empty queue
        consumerPool.shutdown();
        try {
            if (!consumerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Dispatch consumer pool did not drain within 5s — forcing shutdown");
                consumerPool.shutdownNow();
                consumerPool.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            consumerPool.shutdownNow();
        }

        // Drain any messages that landed in the dispatch()/stop() race window and account
        // for them on the dropped-message counter so they aren't a silent loss.
        List<PublishMsg> orphans = new ArrayList<>();
        queue.drainTo(orphans);
        if (!orphans.isEmpty()) {
            droppedMsgsCounter.increment(orphans.size());
            log.warn("Dropped {} orphaned messages during dispatcher shutdown", orphans.size());
        }

        log.info("Message dispatcher stopped");
    }
```

Two changes from the previous implementation:

1. The `awaitTermination` warn message no longer references `queue.size()` — after the new `drainTo` call below it, the size is always 0 by the time anyone could read it. The new orphan-log line below replaces it with the truthful count.
2. New block between `awaitTermination` handling and the final `Message dispatcher stopped` log: drain to a list, increment the meter, and warn-log the count when non-zero.

`dispatch()` (lines 80–91) is **unchanged**.

No new imports needed — `java.util.ArrayList` and `java.util.List` are already imported at lines 35 and 37.

### - [ ] Step 4: Run the new test to verify it passes

Run:

```bash
mvn -f lightweight/pom.xml -o test -Dtest=DefaultMsgDispatcherServiceTest#testStop_drainsOrphanedMessagesAndCountsAsDropped
```

Expected: PASS. Single test, one assertion now green that previously failed.

### - [ ] Step 5: Run the full dispatcher test suite

Run:

```bash
mvn -f lightweight/pom.xml -o test -Dtest=DefaultMsgDispatcherServiceTest
```

Expected: 5 tests pass (the 4 existing tests plus the new one). Specifically verify these existing tests still pass — they were resurrected by the Apr 30 quick-fix `260430-itz` and are the regression tripwire from STATUS.md §2:

- `testDispatch_enqueuesMessage`
- `testDispatch_queueFull_dropsAndCountsMessage`
- `testDispatch_doesNotBlock`
- `testStop_returnsQuicklyWhenQueueEmpty`

If `testStop_returnsQuicklyWhenQueueEmpty` fails, suspect: the new drain on an empty queue should be a no-op (`drainTo` returns immediately on an empty `LinkedBlockingQueue`), so total `stop()` time should be unchanged. If timing is regressed, double-check no extra blocking call was introduced.

### - [ ] Step 6: Run the broader lightweight test suite

Run:

```bash
mvn -f lightweight/pom.xml -o test
```

Expected: all lightweight unit tests pass. This catches any cross-test interaction with the dispatcher (other tests that start/stop the dispatcher and could be affected by the new log line or counter behavior).

If a failure is unrelated to dispatcher changes, note it but do not attempt to fix in this task — STATUS.md §7 #15 already calls out that there is no CI gate on the lightweight Maven project, so latent failures from elsewhere are possible.

### - [ ] Step 7: Commit

Stage exactly the two files we touched, then commit:

```bash
git add \
  lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java \
  lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherServiceTest.java

git commit -m "$(cat <<'EOF'
fix(dispatch): drain orphaned queue messages during dispatcher shutdown

After consumeLoop threads exit, stop() now drains anything left in the
dispatch queue and counts those messages on mqtt.dispatch.dropped.total.
Previously, messages that landed in the queue between consumers exiting
and stop() returning were silently lost — the publisher had already
received PUBACK/PUBCOMP, but no subscriber was reachable and the dropped
counter never ticked. This converts silent loss into observable loss.

The hot path (dispatch()) is unchanged. Closes the practically-relevant
race window from STATUS.md §4 #10. A nanosecond-scale residual remains
(dispatch() pausing for the entire duration of stop()); upgrading to
hard-correct via a ReadWriteLock is documented as the future path.
EOF
)"
```

Verify the commit landed cleanly:

```bash
git log -1 --stat
```

Expected: one commit, two files modified, conventional-commits `fix(dispatch):` prefix, message body explaining the why.

---

## What this plan does NOT cover (per spec, deliberately deferred)

- ReadWriteLock-based hard-correct fix (residual GC-pause window) — upgrade path documented.
- Wiring `mvn -f lightweight/pom.xml verify` into CI — separate STATUS.md §7 #15 follow-up.
- Documentation updates beyond the commit message (e.g., editing STATUS.md to tick item #10) — keep the change atomic; STATUS.md sweep belongs to a separate doc-drift commit.
