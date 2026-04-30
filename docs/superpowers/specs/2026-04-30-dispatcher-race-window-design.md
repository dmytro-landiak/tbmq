# Dispatcher `dispatch()` / `stop()` Race Window — Design

- **Date:** 2026-04-30
- **Component:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java`
- **STATUS.md item:** §4 #10 — "`DefaultMsgDispatcherService.dispatch()` race window … production-acceptable; tests cover the happy path."
- **Goal:** Move this from "production-acceptable known debt" to "fixed (modulo nanosecond GC-pause window)."

## Problem

`DefaultMsgDispatcherService.dispatch()` checks `running`, then calls `queue.offer()`. Between those two operations, `stop()` can:

1. Flip `running = false`
2. Call `consumerPool.shutdown()` → consumers see `running == false`, do non-blocking `poll()`, get nothing, break out of `consumeLoop`
3. `awaitTermination` returns successfully

…and *then* `dispatch()`'s `queue.offer(msg)` lands. The message sits in the queue forever — never delivered, never counted on `mqtt.dispatch.dropped.total`. The publisher already received its PUBACK (QoS 1) or will receive PUBCOMP (QoS 2), so from the publisher's perspective the broker accepted the message.

The race only fires during JVM shutdown. Subscribers are tearing down with the broker, so they wouldn't have received the message even if delivery succeeded. STATUS.md flags it "production-acceptable" for that reason. We fix it because *silent loss* is worse than *counted loss* — observability matters more than the rare lost delivery.

## Approach: Practically-Closed (Drain at end of `stop()`)

**Hot path (`dispatch()`) is unchanged.** Zero added cost on every published message.

After `awaitTermination` returns in `stop()`, drain whatever is still in the queue, count those messages on `mqtt.dispatch.dropped.total`, and log how many we drained.

The race window is dramatically narrowed: any message that lands between "consumers exit" and "stop() returns" gets accounted for instead of silently rotting in the queue.

### What this does NOT close

A residual hair-thin window remains: if a `dispatch()` call pauses (GC, OS preemption) between reading `running` and calling `offer()` for *longer* than the entire `stop()` sequence (5s `awaitTermination` + drain), its `offer()` lands after our drain finishes. That message is still silently lost.

This residual is acceptable for R1 lightweight where `stop()` only fires at JVM shutdown. To upgrade to *hard-correct* later, wrap `dispatch()` in a `ReadWriteLock.readLock()` and `running = false` in `lock.writeLock()` — no other code changes.

### Why not hard-correct now

- Cost: lock acquire on every `dispatch()` call, on the hot path of every published message.
- Benefit: closes a window only relevant during JVM shutdown when subscribers are tearing down anyway.
- ROI doesn't justify it for R1. The upgrade path is trivial if benchmarks ever show this as a real concern.

## Implementation Sketch

```java
@Override
public void stop() {
    running = false;
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

    // Drain any messages that landed in the dispatch()/stop() race window
    // and account for them on the dropped-message counter so they aren't a silent loss.
    List<PublishMsg> orphans = new ArrayList<>();
    queue.drainTo(orphans);
    if (!orphans.isEmpty()) {
        droppedMsgsCounter.increment(orphans.size());
        log.warn("Dropped {} orphaned messages during dispatcher shutdown", orphans.size());
    }

    log.info("Message dispatcher stopped");
}
```

### Notes on the change

1. The `queue.size()` reference in the existing `awaitTermination` warn line is removed because, after `drainTo`, the size is always 0. The orphan log replaces it with the truthful number.
2. `BlockingQueue.drainTo` is the right primitive — it removes all currently available elements in one call.
3. `dispatch()` is **not** modified.

## Testing

The race itself is non-deterministic and not worth trying to reproduce in a unit test. The drain logic is deterministic and is what we test:

1. **Drain test** — start the dispatcher, prevent consumers from emptying the queue (cleanest approach: override `consumeLoop` to a no-op via a test subclass, or submit messages and immediately call `stop()` so consumers don't get scheduled), call `dispatch()` N times, then call `stop()`. Assert:
   - `mqtt.dispatch.dropped.total` increased by exactly N
   - Queue is empty after `stop()` returns
   - WARN log line "Dropped N orphaned messages during dispatcher shutdown" was emitted
2. **Happy-path test** — existing dispatcher tests must continue to pass. In normal operation consumers drain everything before `stop()`, the orphan list is empty, and only `Message dispatcher stopped` is logged.
3. **Regression check** — the three dispatcher tests resurrected by the Apr 30 quick-fix `260430-itz` must still pass.

## Closes

- STATUS.md §4 #10 → moves from "known debt, production-acceptable" to "fixed (modulo nanosecond GC-pause residual)."
- §7 #15 indirectly: documents that this kind of regression should also be caught by adding `mvn -f lightweight/pom.xml verify` to CI (separate item).

## Out of scope

- Adding the `ReadWriteLock` for hard-correctness (deferred upgrade path).
- Wiring CI to run the lightweight Maven project on PRs (separate STATUS.md item).
- The `ClientActor.java:203-215` displaced-actor leak (STATUS.md §4 #9, separate design).
