---
phase: 07-hardening-and-docker-release
fixed_at: 2026-04-11T00:00:00Z
review_path: .planning/phases/07-hardening-and-docker-release/07-REVIEW.md
iteration: 1
findings_in_scope: 6
fixed: 6
skipped: 0
status: all_fixed
---

# Phase 07: Code Review Fix Report

**Fixed at:** 2026-04-11T00:00:00Z
**Source review:** .planning/phases/07-hardening-and-docker-release/07-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 6
- Fixed: 6
- Skipped: 0

## Fixed Issues

### CR-01: Session takeover sets old session to DISCONNECTED before stopping its actor

**Files modified:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java`
**Commit:** 6dc0166e0
**Applied fix:** Added explicit cleanup of the displaced session's QoS state maps (`inboundQos2`, `outboundQos1`, `outboundQos2`) and packet ID allocator during client takeover. The reviewer's suggested fix (stop the old actor via `actorSystem.stop()`) was not applied because in this architecture there is only ONE actor per clientId -- the old and new sessions share the same actor, and stopping it by actorId would destroy the current actor processing the CONNECT. Instead, the valid concern about resource cleanup was addressed by clearing the old session's QoS state and releasing packet IDs before closing the old channel. Status: fixed: requires human verification (reviewer's architectural assumption about separate old/new actors was incorrect; the adapted fix addresses the valid resource cleanup concern).

### CR-02: `DefaultMsgDispatcherService.dispatch()` dereferences `queue` before `start()` is called

**Files modified:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java`
**Commit:** 662fd2405
**Applied fix:** Added a guard at the top of `dispatch()` that checks `!running || queue == null` and returns early with a debug log message. This prevents NPE if `dispatch()` is called before `start()` completes or after `stop()` has been called.

### WR-01: `processConnect` logs client-requested `cleanSession` value instead of enforced broker value

**Files modified:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java`
**Commit:** b3e1f434e
**Applied fix:** Changed the connect log line to always show `cleanSession=true` (the enforced value) and append `[client requested false]` when the client's original request differed, providing both accurate operational state and diagnostic visibility.

### WR-02: `DefaultMsgDispatcherService.stop()` calls `shutdownNow()` immediately

**Files modified:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java`
**Commit:** 153648eca
**Applied fix:** Replaced immediate `shutdownNow()` with a graceful two-phase shutdown: first `shutdown()` with a 5-second drain window via `awaitTermination`, then `shutdownNow()` only if the drain timeout expires. This gives in-flight messages a chance to be delivered before interrupting consumer threads, respecting the MQTT at-least-once semantic for already-acknowledged publishes.

### WR-03: `tryBasicAuth` allows authentication with no password when credentials have `password=null`

**Files modified:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/DefaultLightweightAuthService.java`
**Commit:** 1201e82d7
**Applied fix:** Replaced the `if (password != null)` guard with an explicit null-password rejection: if `basicCreds.getPassword() == null`, the method now logs a warning and returns `AuthResult.failure()`. The password comparison now always runs (no longer conditionally skipped), closing the authentication bypass for misconfigured credentials with null passwords.

### WR-04: `StartupWarningService.checkStartupWarnings` always adds retained message warning making condition dead code

**Files modified:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/install/StartupWarningService.java`
**Commit:** 5d25fbf3d
**Applied fix:** Moved the R1 retained-message limitation notice out of the `warnings` list and into a standalone `log.info()` call. The WARNING banner now only appears when there are actual misconfigurations (TLS not configured, RocksDB not volume-mounted), reducing alert fatigue for correctly-configured brokers.

---

_Fixed: 2026-04-11T00:00:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
