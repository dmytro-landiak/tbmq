---
quick_id: 260430-itz
phase: quick-260430-itz
plan: 1
type: quick
mode: fix
subsystem: lightweight/dispatch
tags: [shutdown, lifecycle, executor-pool, regression-test]
requirements:
  - QUICK-260430-itz
key-files:
  modified:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherServiceTest.java
decisions:
  - "consumeLoop uses bounded queue.poll(100ms) while running and non-blocking queue.poll() when stopping; consumers actively observe the running flag and exit cleanly."
  - "stop() body deliberately not changed — the 5s awaitTermination + fallback shutdownNow remain as a safety net for pathological cases."
  - "Test setUp() re-enables running flag after start()+stop() because the !running guard added in CR-02 (commit 662fd2405) silently dropped test messages at dispatch() entry; restoring running=true preserves the original 'consumers halted, queue retains messages' test pattern."
metrics:
  duration_minutes: 6
  completed_at: "2026-04-30"
  tests_total: 4
  tests_passed: 4
  measured_stop_elapsed_ms: 100
---

# Quick 260430-itz: Fix DefaultMsgDispatcherService Shutdown

## One-liner

`consumeLoop()` now polls with a 100ms ceiling while running and drains non-blocking once `running=false`, so dispatcher `stop()` returns in ~100ms instead of always waiting the full 5s `awaitTermination` timeout.

## What changed

**Source (`DefaultMsgDispatcherService.java`, lines 148–167):**

- Replaced unconditional `queue.take()` (unbounded blocking) with a two-mode poll:
  - While `running=true`: `queue.poll(100, TimeUnit.MILLISECONDS)` — blocks briefly so the loop periodically observes the `running` flag.
  - Once `running=false`: `queue.poll()` — non-blocking; drains remaining messages then exits via `break`.
- Preserved the `InterruptedException` handler with proper interrupt-bit propagation (`Thread.currentThread().interrupt(); break;`).
- Preserved the existing `log.error("Error in dispatch consumer loop", e)` — same message, parameterized form, no string concatenation.
- No new imports — `TimeUnit` was already imported (line 44).

**Tests (`DefaultMsgDispatcherServiceTest.java`):**

- Added `testStop_returnsQuicklyWhenQueueEmpty` (regression test): builds a fresh dispatcher with 2 live consumer threads against a 16-slot empty queue, calls `stop()`, asserts elapsed < 1000ms.
- Updated `setUp()` to re-enable the `running` flag after `start()+stop()` (see Deviations below).

## What deliberately did NOT change

- **`stop()` body is byte-for-byte unchanged.** Its existing logic
  (`running=false → consumerPool.shutdown() → awaitTermination(5s) → fallback shutdownNow()`)
  becomes correct once `consumeLoop()` actively reads `running`. The 5s ceiling and
  `shutdownNow()` fallback remain intact as a safety net for pathological cases (e.g.,
  a stuck `deliverToSubscribers` call).
- No public API changes; no behavioural change for normal message dispatch.

## Verification

`mvn test -Dtest=DefaultMsgDispatcherServiceTest` (run from `lightweight/`):

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.282 s
[INFO] BUILD SUCCESS
```

All four tests pass:
- `testDispatch_enqueuesMessage` — 25ms
- `testDispatch_queueFull_dropsAndCountsMessage` — 15ms
- `testDispatch_doesNotBlock` — 121ms
- `testStop_returnsQuicklyWhenQueueEmpty` — 982ms (includes shared-context setUp + 2 dispatcher start/stop cycles; the actual `localDispatcher.stop()` measurement is **100ms**, captured by tightening the assertion to `< 50L` momentarily as a probe).

**Measured `stop()` elapsed on empty queue: 100 ms** (well under 1000ms — typical
~100ms predicted by the plan, observed exactly that).

**The `Dispatch consumer pool did not drain within 5s — 0 messages may be lost`
warning is no longer emitted on a clean shutdown** — confirmed by the absence of
that WARN line in the surefire output after the fix (it was present in the
pre-fix RED run).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 – Bug] Pre-existing test setUp incompatible with `dispatch()` `!running` guard**

- **Found during:** GREEN phase verification (after applying the planned `consumeLoop` change, three pre-existing tests failed with `Expected size: 1 but was: 0`).
- **Issue:** Phase 7 commit `662fd2405` (`fix(07): CR-02 guard dispatch() against null queue before start() is called`) added an early-return in `dispatch()` when `!running`. The test `setUp()` calls `start()` then `stop()` to halt consumer threads (per the Phase 03 decision *"start()+stop() in unit test setUp() initializes queue/counter fields without running consumer threads for deterministic testing"*). After `stop()`, `running=false`, so every subsequent `dispatch()` call silently dropped the message before reaching the queue. The three pre-existing tests had been failing since CR-02 landed — independent of this quick fix. Verified by running the suite at `HEAD~1` (pre-fix); all three fail with the same assertion mismatch.
- **Fix:** Added one line to `setUp()` after `dispatcher.stop()`:
  ```java
  ReflectionTestUtils.setField(dispatcher, "running", true);
  ```
  This restores the original test intent — consumer pool shut down (queue won't drain) AND `running=true` (dispatch() accepts messages). No production code path is affected.
- **Files modified:** `lightweight/src/test/java/.../DefaultMsgDispatcherServiceTest.java` (setUp only).
- **Commit:** `e4378acf8` (combined with the GREEN source change).

The plan's `must_haves.truths[2]` ("All existing dispatcher tests still pass — running message dispatch behaviour is unchanged") was based on the planner's belief these tests were green. They were not. Fixing the test scaffold here brings the suite back to a healthy baseline alongside the new regression test.

## Authentication Gates

None.

## Commits

| Step  | Commit       | Type   | Files                                          |
| ----- | ------------ | ------ | ---------------------------------------------- |
| RED   | `f0578b36a`  | test   | DefaultMsgDispatcherServiceTest.java           |
| GREEN | `e4378acf8`  | fix    | DefaultMsgDispatcherService.java + Test.java   |

## Self-Check: PASSED

Verified files exist and commits are present:

- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java` — FOUND
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherServiceTest.java` — FOUND
- Commit `f0578b36a` — FOUND
- Commit `e4378acf8` — FOUND
- `grep -n "queue.take\|queue.poll" .../DefaultMsgDispatcherService.java` → only `queue.poll(...)` references remain (no `queue.take()`).
- `grep -c "running" .../DefaultMsgDispatcherService.java` → 10 (was 8; +2 for new consumeLoop reads).
