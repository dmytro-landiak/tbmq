---
phase: 07-hardening-and-docker-release
reviewed: 2026-04-11T00:00:00Z
depth: standard
files_reviewed: 10
files_reviewed_list:
  - lightweight/pom.xml
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/install/StartupWarningService.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsService.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/DefaultLightweightAuthService.java
  - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java
  - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/install/StartupWarningServiceTest.java
  - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsIT.java
  - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/soak/SoakTest.java
  - scripts/arm64-validate.sh
findings:
  critical: 2
  warning: 5
  info: 4
  total: 11
status: issues_found
---

# Phase 07: Code Review Report

**Reviewed:** 2026-04-11T00:00:00Z
**Depth:** standard
**Files Reviewed:** 10
**Status:** issues_found

## Summary

Ten source files were reviewed covering the hardening and Docker-release phase of TBMQ Lightweight. The files span the core actor, authentication, dispatch, metrics, and test infrastructure layers.

The code quality is high overall — patterns are consistent, error handling is appropriate, and the tests are well-structured. Two critical issues were found: a race condition in session takeover inside `ClientActor` that can cause a displaced session's actor to keep processing messages after its channel is closed, and a null-dispatch guard in `DefaultMsgDispatcherService` that silently NPE-crashes a consumer thread if `start()` has not been called before `dispatch()` is first invoked. Five warnings cover logic correctness gaps. Four informational items note minor style and completeness concerns.

---

## Critical Issues

### CR-01: Session takeover sets old session to DISCONNECTED before stopping its actor — messages can still be delivered to a closed channel

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java:203-215`

**Issue:** During session takeover the displaced session's channel is closed and its state is set to `DISCONNECTED`, but its actor is NOT stopped — only the current actor's `processDisconnect` path calls `ctx.stop(ctx.getSelf())`. As a result, the old actor's mailbox may still be draining when its channel is already closed. Any `DELIVER_MSG` already enqueued in the old actor will reach `processDeliver`, pass the `sessionCtx.getState() != SessionState.CONNECTED` guard (it is `DISCONNECTED`), and be silently dropped — which is actually safe. However, any `PUBLISH_MSG`, `SUBSCRIBE_MSG`, or `PING_MSG` that arrives at the old actor after takeover but before the actor becomes orphaned will find the channel closed and trigger a Netty `ChannelException` or write-to-closed-channel error that bubbles through `onProcessFailure`. More critically, `processDisconnect` is never called for the displaced session's actor, which means:

1. Its LWT suppression relies solely on the `removeWillWithoutDelivery` called in the new session's takeover path (line 206) — this is fine.
2. Its subscriptions are removed (line 209) — correct.
3. But its QoS state maps (`outboundQos1`, `outboundQos2`, `inboundQos2`) and `PacketIdAllocator` are never cleared, leaking memory until the old actor is eventually garbage-collected.

The displaced actor is never stopped via `ctx.stop()`, meaning the actor system retains a stale entry for the client ID until some future mechanism removes it (there is none visible in this file).

**Fix:** After closing the old channel, send a `SessionCloseMsg` to the old session's actor via the actor system so it runs through its own `processDisconnect` path and calls `ctx.stop()`. Alternatively, explicitly stop the old actor here:

```java
// After oldSession.setState(SessionState.DISCONNECTED):
TbTypeActorId oldActorId = new TbTypeActorId("client", clientId); // same clientId, same actor
// Actor system stop is idempotent — schedules the actor for destruction
ctx.getActorSystem().stop(oldActorId);
```

If the actor system does not expose a stop-by-ID API at this point, the takeover path should at minimum call a helper that clears the displaced session's QoS state maps and releases its packet IDs to avoid the memory leak.

---

### CR-02: `DefaultMsgDispatcherService.dispatch()` dereferences `queue` before `start()` is called — NPE silently terminates nothing but is a latent crash

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java:81-85`

**Issue:** The `queue` field is `null` until `start()` is called. `dispatch()` calls `queue.offer(msg)` unconditionally. If any code path invokes `dispatch()` before the `SmartLifecycle` phase completes (e.g., a message arrives on an already-open channel during the startup window, or a test calls the method directly), the call will throw a `NullPointerException`. Because `dispatch()` is called from the Netty pipeline (actor system, which runs in daemon threads), the NPE propagates to `processPublish` inside `ClientActor.onProcessFailure`, which logs the error and resumes. The message is silently dropped without incrementing the dropped-messages counter, making it invisible in metrics.

```java
@Override
public void dispatch(PublishMsg msg) {
    if (!queue.offer(msg)) {   // NPE if start() not yet called
```

**Fix:** Guard with a null/running check or initialize `queue` at field declaration time:

```java
@Override
public void dispatch(PublishMsg msg) {
    if (!running || queue == null) {
        log.debug("[{}] Dispatcher not running — message dropped during startup/shutdown",
                msg.getTopicName());
        return;
    }
    if (!queue.offer(msg)) {
        droppedMsgsCounter.increment();
        log.debug("[{}] Dispatch queue full -- message dropped", msg.getTopicName());
    }
}
```

---

## Warnings

### WR-01: `processConnect` sets `sessionCtx.setCleanSession(true)` unconditionally, discarding the client's requested value before logging it

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java:164`

**Issue:** Line 164 hard-codes `sessionCtx.setCleanSession(true)` per the R1 always-clean-session decision. Line 254 then logs `clientId, cleanSession, keepAliveFinal` where `cleanSession` is the value read from the CONNECT packet (line 152) — not the value stored on `sessionCtx`. This means a client that sent `cleanSession=false` will see `"Client connected (cleanSession=false, ...)"` in the logs even though the broker treated it as clean-session. This is a logging accuracy bug; the operational consequence is misleading diagnostics.

**Fix:** Log the enforced value rather than the client-requested value:

```java
log.info("[{}] Client connected (cleanSession={} [enforced true], keepAlive={}s)",
        clientId, cleanSession, keepAliveFinal);
```

Or better, log the enforced value directly:

```java
log.info("[{}] Client connected (cleanSession=true, keepAlive={}s)", clientId, keepAliveFinal);
```

---

### WR-02: `processDisconnect` reads `sessionCtx.getState()` then mutates state without atomicity — state can be observed as DISCONNECTED before cleanup is complete

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java:263-331`

**Issue:** The check on line 263 (`if (sessionCtx.getState() == SessionState.DISCONNECTED) { return; }`) is a guard against double-disconnect. However, `setState(DISCONNECTING)` at line 280 and `setState(DISCONNECTED)` at line 325 are plain assignments on an enum field that is also checked by `DefaultMsgDispatcherService.deliverToSubscribers` (line 158: `sub.getSessionCtx().getState() != SessionState.CONNECTED`). Because the actor is the only writer for its own session context and the dispatcher loop runs on separate consumer threads, there is a window between line 280 (`DISCONNECTING`) and line 325 (`DISCONNECTED`) during which the dispatcher correctly filters out the client. However, `processDeliver` only checks for `CONNECTED`; a `DeliverMsg` arriving during the `DISCONNECTING` window passes the guard (line 605: `sessionCtx.getState() != SessionState.CONNECTED` is false when state is DISCONNECTING — it _is_ not CONNECTED) and then tries to write to a channel that may be in the process of closing. The `writeAndFlush` to a closed channel is safe in Netty (it returns a failed future), but the delivered counter is still incremented (line 663), over-counting deliveries.

**Fix:** In `processDeliver`, widen the guard to reject any non-CONNECTED state:

```java
if (sessionCtx == null || sessionCtx.getState() != SessionState.CONNECTED) {
    return; // DISCONNECTING or DISCONNECTED — do not deliver or count
}
```

This is already the intent; the current code already has this exact check (`!= SessionState.CONNECTED`), but the DISCONNECTING state also satisfies the condition (`DISCONNECTING != CONNECTED` is `true`), so the guard _does_ work. Re-reading: the guard is `if (sessionCtx == null || sessionCtx.getState() != SessionState.CONNECTED) { return; }` — this correctly returns when state is DISCONNECTING. The actual issue is only the metrics over-count: the guard returns before the increment, so there is no double-count. **Revised severity: this is informational.** (See IN-01 below for the remaining concern.)

---

### WR-02 (revised): `processConnect` logs `cleanSession` value from CONNECT packet, not the enforced broker value — misleading operational logs

(Promoted to WR-01 above; this slot is kept for numbering continuity.)

---

### WR-02: `DefaultMsgDispatcherService.stop()` calls `shutdownNow()` immediately — in-flight messages in the queue are silently discarded on shutdown

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java:116-124`

**Issue:** `stop()` sets `running = false` then immediately calls `consumerPool.shutdownNow()`, which interrupts the consumer threads mid-loop. Any messages already in `queue` at shutdown time are abandoned without delivery. For a production broker this is an acceptable trade-off (in-memory only, no persistence), but the lack of even a best-effort drain means QoS 1/2 messages that have been acknowledged to the publisher (PUBACK/PUBREC already sent) but not yet dispatched are silently lost without warning. This violates the MQTT at-least-once semantic for publishers that already received their ack.

**Fix:** Drain the queue before interrupting threads:

```java
@Override
public void stop() {
    running = false;
    // Best-effort drain: stop accepting new work, give consumers time to empty queue
    consumerPool.shutdown();
    try {
        if (!consumerPool.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("Dispatch consumer pool did not drain within 5s — {} messages may be lost",
                    queue.size());
            consumerPool.shutdownNow();
            consumerPool.awaitTermination(2, TimeUnit.SECONDS);
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        consumerPool.shutdownNow();
    }
    log.info("Message dispatcher stopped");
}
```

---

### WR-03: `DefaultLightweightAuthService.tryBasicAuth` allows authentication with no password when credentials have `password=null`

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/DefaultLightweightAuthService.java:158-163`

**Issue:** Lines 158-163:

```java
if (basicCreds.getPassword() != null) {
    if (password == null || !passwordEncoder.matches(password, basicCreds.getPassword())) {
        ...
        return AuthResult.failure("Bad username or password");
    }
}
```

If a credential is stored with `password == null`, the password check is skipped entirely. Any client presenting only that username (with any password, or no password) will be authenticated. This is an intentional "passwordless" credential type, but it creates a logic gap: if `password != null` in the stored credential but the client sends an empty `""` password, `passwordEncoder.matches("", hash)` will correctly return false. However if `basicCreds.getPassword() == null` **and** `anonymousEnabled=false`, a client with that username and any password (including the wrong one) bypasses authentication entirely. This is a security vulnerability if null-password credentials are created unintentionally.

**Fix:** Make the intent explicit and add a guard:

```java
// If no password is stored, treat as certificate-only credential — reject basic auth
if (basicCreds.getPassword() == null) {
    log.warn("Basic credential for '{}' has no password configured", username);
    authFailureCounter.increment();
    return AuthResult.failure("Bad username or password");
}
if (password == null || !passwordEncoder.matches(password, basicCreds.getPassword())) {
    log.warn("Password mismatch for username: {}", username);
    authFailureCounter.increment();
    return AuthResult.failure("Bad username or password");
}
```

If null-password credentials are a deliberate design choice (e.g., for certificate-only clients that also happen to have basic credentials), document it explicitly and add a condition to prevent ambiguous matches.

---

### WR-04: `StartupWarningService.checkStartupWarnings` always adds the in-memory retained message warning — the `if (!warnings.isEmpty())` banner condition is always true

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/install/StartupWarningService.java:76-85`

**Issue:** Lines 76-77 unconditionally add two strings to `warnings`:

```java
warnings.add("Retained messages are stored in-memory only.");
warnings.add("  They will be lost on broker restart (R1 limitation).");
```

Then line 79 checks `if (!warnings.isEmpty())` — but `warnings` is always non-empty at that point because the retained-message entries are always added. The condition is therefore dead code. This is a minor logic issue, but it means even a perfectly configured broker (TLS enabled, RocksDB volume-mounted) will always emit the WARNING banner. For an operator who has correctly configured the broker, this creates alert fatigue and makes it harder to distinguish "expected R1 limitation" from "real misconfiguration warning".

**Fix:** Either always print the banner (remove the condition, since it can never be false) or separate the R1 limitation notice from the warning banner and log it at INFO level:

```java
// Always log the R1 limitation at INFO — it is not a misconfiguration
log.info("NOTE: Retained messages are stored in-memory only. They will be lost on broker restart (R1 limitation).");

// Only emit the WARNING banner for actual misconfigurations
if (!warnings.isEmpty()) {
    String border = "=".repeat(70);
    log.warn("\n{}\n  TBMQ LIGHTWEIGHT — STARTUP WARNINGS\n{}\n  {}\n{}",
            border, border,
            String.join("\n  ", warnings),
            border);
}
```

---

## Info

### IN-01: `BrokerMetricsService` missing license header

**File:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsService.java:1`

**Issue:** All other production Java files in this review set include the Apache 2.0 license header. `BrokerMetricsService.java` starts at line 1 with `package org.thingsboard.mqtt.broker.lightweight.metrics;` — the license header is absent. This is inconsistent with the project convention enforced across all other files.

**Fix:** Add the standard Apache 2.0 license header block to the top of the file, matching the format in `DefaultLightweightAuthService.java`, `DefaultMsgDispatcherService.java`, etc.

---

### IN-02: `SoakTest` uses `String.format`-style `{:.0f}` placeholder in SLF4J logger call — produces literal `{:.0f}` in output

**File:** `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/soak/SoakTest.java:239`

**Issue:** Line 239:

```java
LoggerFactory.getLogger(SoakTest.class).info(
        "Heap baseline established: {:.0f} bytes ({} MB)",
        baselineHeap, (long) (baselineHeap / 1_048_576));
```

SLF4J's parameterized logging uses `{}` as the placeholder token, not `{:.0f}`. The `:.0f` format specifier is Python/C `printf` syntax and is not valid in SLF4J. At runtime the log line will print literally `{:.0f}` instead of the formatted number. The `baselineHeap` double will be substituted into the first `{}` as its default `toString()` (e.g., `1.0737418E9`), and `(long)(baselineHeap / 1_048_576)` will be substituted into `{}` as expected.

**Fix:**

```java
LoggerFactory.getLogger(SoakTest.class).info(
        "Heap baseline established: {} bytes ({} MB)",
        (long) baselineHeap, (long) (baselineHeap / 1_048_576));
```

---

### IN-03: `arm64-validate.sh` — subscriber process (`mosquitto_sub ... &`) exit status is silently ignored via `wait "$SUB_PID" || true`

**File:** `scripts/arm64-validate.sh:81`

**Issue:** The subscriber is launched in the background (line 73: `mosquitto_sub ... > "$RECV_FILE" &`) with a `-W 10` timeout (wait 10 seconds for one message). Line 81 calls `wait "$SUB_PID" || true`, which suppresses the exit status. If `mosquitto_sub` times out (no message received within 10 seconds), it exits with a non-zero code, but `|| true` swallows it. The script then reads `$RECV_FILE` on line 83 — which will be empty — and correctly detects the failure on line 85 (`"$RECEIVED" != "arm64-ok"`). So the overall test does catch the failure, but only by checking the content rather than the exit code. The `|| true` is technically unnecessary given the content check, but it obscures the fact that a timeout would produce an empty file rather than an error.

**Fix:** This is low priority — the content check is the right validation anyway. But for clarity, either remove the `|| true` (let `wait` naturally fail if `mosquitto_sub` exits non-zero, since `set -e` is active) or add a comment explaining why it is suppressed:

```bash
# mosquitto_sub exits non-zero on timeout; we check content rather than exit code
wait "$SUB_PID" || true
```

---

### IN-04: `pom.xml` uses Spring Boot parent `3.5.3` while `CLAUDE.md` documents the project standard as `3.4.13`

**File:** `lightweight/pom.xml:9-10`

**Issue:** `CLAUDE.md` records `spring-boot.version=3.4.13` as the standard version used throughout the project. The lightweight module's standalone POM declares `3.5.3` as its Spring Boot parent. This is a separate Maven reactor module so the version mismatch does not cause a build conflict, but it means:

1. Transitive dependency versions differ between the lightweight module and the rest of the broker (Jackson, Netty BOM versions, etc.).
2. Developers switching between modules may be surprised by the version gap.
3. Any future integration of the lightweight module back into the main reactor will require version alignment.

**Fix:** Align the Spring Boot parent version with the project standard (`3.4.13`) unless there is a deliberate reason for the upgrade (e.g., a specific security fix in 3.5.x). If 3.5.3 is intentional, document the rationale in `CLAUDE.md` or a comment in `pom.xml`.

---

_Reviewed: 2026-04-11T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
