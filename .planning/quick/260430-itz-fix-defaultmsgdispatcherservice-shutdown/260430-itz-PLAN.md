---
quick_id: 260430-itz
phase: quick-260430-itz
plan: 1
type: quick
mode: fix
wave: 1
depends_on: []
files_modified:
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java
  - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherServiceTest.java
autonomous: true
requirements:
  - QUICK-260430-itz
must_haves:
  truths:
    - "DefaultMsgDispatcherService.stop() returns within ~100ms when the queue is empty (no full 5s wait)"
    - "Consumer threads observe running=false and exit cleanly on their own — shutdownNow() fallback is no longer reached on a clean shutdown"
    - "All existing dispatcher tests still pass — running message dispatch behaviour is unchanged"
  artifacts:
    - path: "lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java"
      provides: "consumeLoop using bounded poll() that respects running flag"
      contains: "queue.poll"
    - path: "lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherServiceTest.java"
      provides: "test verifying stop() returns quickly when queue is empty"
      contains: "testStop_returnsQuicklyWhenQueueEmpty"
  key_links:
    - from: "DefaultMsgDispatcherService.stop()"
      to: "DefaultMsgDispatcherService.consumeLoop()"
      via: "running flag (volatile boolean)"
      pattern: "running\\s*=\\s*false"
---

<objective>
Fix the `DefaultMsgDispatcherService` shutdown path so it terminates immediately when the
dispatch queue is empty, instead of always waiting the full 5-second `awaitTermination`
timeout.

Purpose: The current shutdown logs `Dispatch consumer pool did not drain within 5s — 0 messages
may be lost` after a 5s pause on every clean shutdown. Root cause: consumer threads block on
`queue.take()` and never read the `running` flag, so `consumerPool.shutdown()` cannot complete
until the fallback `shutdownNow()` interrupts them.

Output:
- `consumeLoop()` rewritten to use bounded `queue.poll(...)` so consumer threads actively
  observe the `running` flag and exit cleanly when it flips to false.
- A new unit test that asserts `stop()` returns in under 1 second when the queue is empty,
  preventing regression.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
</execution_context>

<context>
@.planning/STATE.md
@CLAUDE.md
@lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java
@lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherServiceTest.java

<interfaces>
<!-- Existing class shape relevant to this change. No new public API. -->

`DefaultMsgDispatcherService`:
- `private volatile boolean running = false;` — set true in `start()`, false in `stop()`.
- `private BlockingQueue<PublishMsg> queue;` — assigned in `start()` from `queueFactory.createQueue()`.
- `private ExecutorService consumerPool;` — `consumerThreads` workers each running `consumeLoop()`.
- `public void start()` / `public void stop()` — `SmartLifecycle` hooks.
- `private void consumeLoop()` — current body uses `queue.take()` (unbounded blocking). This is the only method we change.
- `private void deliverToSubscribers(PublishMsg msg)` — unchanged; called from `consumeLoop()`.
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Make consumeLoop respect the running flag and add fast-shutdown regression test</name>
  <files>
    lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java
    lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherServiceTest.java
  </files>
  <behavior>
    - Test 1 (new): `testStop_returnsQuicklyWhenQueueEmpty` — fresh dispatcher with an empty
      queue and live consumer threads; calling `stop()` returns in well under 1 second
      (assert <1000ms; expected ~100ms). This proves consumers observe `running=false`.
    - Existing tests unchanged in intent: `testDispatch_enqueuesMessage`,
      `testDispatch_queueFull_dropsAndCountsMessage`, `testDispatch_doesNotBlock` must still
      pass. The setUp pattern (start()+stop() to halt consumers before each test) continues
      to work because new shutdown path is faster, not slower, for an empty queue.
  </behavior>
  <action>
1. Rewrite `consumeLoop()` in
   `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java`
   to use bounded poll() instead of unconditional take(). Replace the existing method body
   (currently lines ~148-160) with:

   ```java
   private void consumeLoop() {
       while (!Thread.currentThread().isInterrupted()) {
           try {
               // While running, block briefly so we periodically observe the running flag.
               // Once running=false, drain remaining messages without blocking, then exit.
               PublishMsg msg = running
                       ? queue.poll(100, TimeUnit.MILLISECONDS)
                       : queue.poll();
               if (msg != null) {
                   deliverToSubscribers(msg);
               } else if (!running) {
                   break;
               }
           } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
               break;
           } catch (Exception e) {
               log.error("Error in dispatch consumer loop", e);
           }
       }
   }
   ```

   Notes for the executor:
   - Do NOT modify `stop()` — its existing logic
     (running=false → shutdown() → awaitTermination(5) → fallback shutdownNow()) becomes
     correct once `consumeLoop()` actively reads `running`.
   - `TimeUnit` is already imported at line 44; no new imports needed.
   - Keep the existing `log.error("Error in dispatch consumer loop", e)` exactly — same
     message, same parameterized form, no string concatenation.
   - Preserve interrupt-bit propagation in the `InterruptedException` branch
     (`Thread.currentThread().interrupt(); break;`).
   - 4-space indent, brace on same line, matching the rest of the file.

2. Add a focused regression test to
   `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherServiceTest.java`.

   Important context about the existing test class:
   - `setUp()` already calls `dispatcher.start()` then `dispatcher.stop()` so the *shared*
     `dispatcher` field has consumer threads halted (deterministic for queue assertions).
   - This new test needs LIVE consumer threads at the moment `stop()` is invoked, so it
     must build its own dispatcher rather than reusing the field, and then NOT call
     `stop()` from setUp on that new instance.

   Add this test method (place it after `testDispatch_doesNotBlock`, before `buildMsg`):

   ```java
   @Test
   void testStop_returnsQuicklyWhenQueueEmpty() {
       // Build a fresh dispatcher with live consumer threads — do NOT pre-stop it.
       SimpleMeterRegistry localRegistry = new SimpleMeterRegistry();
       LinkedBlockingQueue<PublishMsg> localQueue = new LinkedBlockingQueue<>(16);
       when(queueFactory.createQueue()).thenReturn(localQueue);

       DefaultMsgDispatcherService localDispatcher =
               new DefaultMsgDispatcherService(queueFactory, subscriptionRegistry, actorSystem, localRegistry);
       ReflectionTestUtils.setField(localDispatcher, "consumerThreads", 2);
       localDispatcher.start();

       // With consumers idle on an empty queue, stop() must return well under the 5s
       // awaitTermination ceiling. Generous bound (1s) leaves CI headroom; expected ~100ms.
       long startNanos = System.nanoTime();
       localDispatcher.stop();
       long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

       assertThat(elapsedMs)
               .as("stop() should return quickly when queue is empty (saw %d ms)", elapsedMs)
               .isLessThan(1000L);
   }
   ```

   - All needed imports already present: `SimpleMeterRegistry`, `LinkedBlockingQueue`,
     `ReflectionTestUtils`, `assertThat`, `when`, `Test`.
  </action>
  <verify>
    <automated>cd /home/dlandiak/projects/gsd/tbmq && mvn -pl lightweight -am test -Dtest=DefaultMsgDispatcherServiceTest -q</automated>
  </verify>
  <done>
    - `DefaultMsgDispatcherService.consumeLoop()` uses `queue.poll(...)` (bounded while
      running, non-blocking while stopping); `queue.take()` is gone.
    - `stop()` is byte-for-byte unchanged.
    - `DefaultMsgDispatcherServiceTest` contains `testStop_returnsQuicklyWhenQueueEmpty` and
      it passes (elapsed <1000ms, typical ~100ms).
    - All four dispatcher tests pass under `mvn -pl lightweight -am test
      -Dtest=DefaultMsgDispatcherServiceTest` with no compilation errors and no warnings
      from the change.
    - No new imports added beyond what is already in either file.
  </done>
</task>

</tasks>

<verification>
- Build + test: `mvn -pl lightweight -am test -Dtest=DefaultMsgDispatcherServiceTest` is green.
- Manual sanity check (optional):
  `grep -n "queue.take\|queue.poll" lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java`
  must show only `queue.poll(...)` — no `queue.take()` references remain.
- Manual sanity check (optional): `grep -c "running" .../DefaultMsgDispatcherService.java`
  must increase by at least 1 vs. the pre-fix count (consumeLoop now reads it).
</verification>

<success_criteria>
- `DefaultMsgDispatcherService.stop()` no longer logs "did not drain within 5s" on a clean
  shutdown with an empty queue.
- Time from `stop()` invocation to return is well under 1 second when the queue is empty
  (verified by `testStop_returnsQuicklyWhenQueueEmpty`).
- All pre-existing dispatcher unit tests continue to pass unchanged.
- The 5s `awaitTermination` ceiling and `shutdownNow()` fallback remain intact as a safety
  net for pathological cases.
</success_criteria>

<output>
After completion, create `.planning/quick/260430-itz-fix-defaultmsgdispatcherservice-shutdown/260430-itz-SUMMARY.md`
documenting:
- What was changed (consumeLoop only) and what was deliberately NOT changed (stop() body).
- Test added and observed elapsed time for `stop()` on empty queue.
- Confirmation that the "did not drain within 5s" warning is no longer emitted on clean
  shutdown.
</output>
