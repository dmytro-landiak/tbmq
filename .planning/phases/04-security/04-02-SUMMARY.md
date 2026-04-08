---
phase: 04-security
plan: 02
subsystem: auth
tags: [mqtt, auth, acl, netty, integration-tests, micrometer]

# Dependency graph
requires:
  - phase: 04-01
    provides: LightweightAuthService, AuthorizationRuleService, LightweightCredentialService, AuthResult, AuthRulePatterns
  - phase: 03-message-dispatch
    provides: ClientActor, MqttSessionHandler, MqttChannelInitializer, ClientSessionCtx
provides:
  - Auth enforcement in ClientActor.processConnect() — rejects invalid/anonymous clients with CONNACK NOT_AUTHORIZED
  - ACL enforcement in ClientActor.processPublish() — drops denied messages with counter
  - ACL enforcement in ClientActor.processSubscribe() — rejects denied topics with SUBACK 0x80
  - authRulePatterns field on ClientSessionCtx for per-session ACL state
  - SslHandler threading from MqttChannelInitializer through MqttSessionHandler to ClientActorCreator to ClientActor
  - Micrometer denial counters (mqtt.auth.denied.total with type=publish|subscribe tags)
  - MqttAuthIntegrationTest and MqttAclIntegrationTest proving AUTH-01, AUTH-03, AUTH-04
affects: [04-03]

# Tech tracking
tech-stack:
  added: []
  patterns: [auth-before-session-registration, acl-per-message-drop, deny-by-default-acl]

key-files:
  created:
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttAuthIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttAclIntegrationTest.java
  modified:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/ClientSessionCtx.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActorCreator.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/msg/MqttConnectMsg.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttChannelInitializer.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/AbstractMqttIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttKeepAliveIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttLwtIntegrationTest.java

key-decisions:
  - "Auth check inserted BEFORE session registration in processConnect() — per D-04; avoids registering sessions that will be immediately rejected"
  - "PUBLISH ACL denial sends QoS acks (PUBACK/PUBREC) before dropping — avoids protocol violation on denied messages"
  - "SUBSCRIBE ACL denial returns 0x80 in SUBACK per MQTT 3.1.1 spec 3.9.3"
  - "SslHandler extracted at MqttSessionHandler level and threaded to ClientActor via MqttConnectMsg — clean separation of transport and auth layers"

patterns-established:
  - "Auth-before-registration: authenticate in processConnect() before calling sessionRegistry.registerSession()"
  - "Deny-counter pattern: meterRegistry.counter('mqtt.auth.denied.total', 'type', <type>).increment() on every denial"
  - "defaultConnectOptions() in AbstractMqttIntegrationTest includes tbmq/tbmq credentials — required since auth is enforced"

requirements-completed: [AUTH-01, AUTH-03, AUTH-04, AUTH-05]

# Metrics
duration: 14min
completed: "2026-04-08"
---

# Phase 4 Plan 2: Auth Pipeline Integration Summary

**Auth and ACL wired into MQTT actor pipeline: processConnect() rejects invalid/anonymous with NOT_AUTHORIZED, processPublish() drops with counter, processSubscribe() returns 0x80, all proven by 9 new integration tests.**

## Performance

- **Duration:** 14 min
- **Started:** 2026-04-08T17:14:24Z
- **Completed:** 2026-04-08T17:28:30Z
- **Tasks:** 2
- **Files modified:** 9

## Accomplishments

- Auth check inserted in `ClientActor.processConnect()` before session registration — rejects clients with CONNACK NOT_AUTHORIZED (0x05) and closes channel immediately
- ACL enforcement added to `processPublish()` (drop message + increment `mqtt.auth.denied.total{type=publish}` counter) and `processSubscribe()` (add 0x80 failure code to SUBACK per MQTT 3.1.1 spec 3.9.3)
- `authRulePatterns` field added to `ClientSessionCtx` — set once after auth, used on every pub/sub message for O(1) session-local ACL checks
- `SslHandler` threaded from `MqttChannelInitializer` through `MqttSessionHandler` to `ClientActorCreator` to `ClientActor` via `MqttConnectMsg` — ready for mTLS client certificate auth
- 9 new integration tests passing: 5 auth tests (valid/invalid credentials, anonymous rejection, custom credentials, nonexistent user) and 4 ACL tests (pub allowed/denied, sub SUBACK 0x80, default allow-all)
- All 113 existing tests continue to pass (0 failures, 0 errors, 3 pre-existing skips)

## Task Commits

Each task was committed atomically:

1. **Task 1: Wire auth into ClientActor, MqttSessionHandler, ClientSessionCtx, and channel initializer** - `4c722af4b` (feat)
2. **Task 2: Integration tests for authentication and ACL enforcement** - `4f078b3da` (feat)

## Files Created/Modified

- `lightweight/src/main/java/.../session/ClientSessionCtx.java` - Added `authRulePatterns` field (volatile List<AuthRulePatterns>)
- `lightweight/src/main/java/.../actors/client/msg/MqttConnectMsg.java` - Added `sslHandler` field for mTLS cert threading
- `lightweight/src/main/java/.../actors/client/ClientActor.java` - Auth check in processConnect(), ACL in processPublish()/processSubscribe(), evict on disconnect
- `lightweight/src/main/java/.../actors/client/ClientActorCreator.java` - Added authService, authorizationRuleService, meterRegistry params
- `lightweight/src/main/java/.../server/MqttSessionHandler.java` - Added auth service deps and SslHandler extraction
- `lightweight/src/main/java/.../server/MqttChannelInitializer.java` - Inject auth services, pass to MqttSessionHandler
- `lightweight/src/test/.../mqtt/AbstractMqttIntegrationTest.java` - Added auth helpers, defaultConnectOptions with tbmq/tbmq credentials
- `lightweight/src/test/.../mqtt/MqttAuthIntegrationTest.java` - 5 auth scenario tests (AUTH-01, AUTH-04)
- `lightweight/src/test/.../mqtt/MqttAclIntegrationTest.java` - 4 ACL scenario tests (AUTH-03)
- `lightweight/src/test/.../mqtt/MqttKeepAliveIntegrationTest.java` - Updated raw packet builder with credentials
- `lightweight/src/test/.../mqtt/MqttLwtIntegrationTest.java` - Updated raw packet builder with credentials

## Decisions Made

- Auth check inserted BEFORE session registration in `processConnect()` — per D-04; avoids registering sessions that will be immediately rejected and preventing resource leaks
- PUBLISH ACL denial still sends QoS 1 PUBACK / QoS 2 PUBREC before dropping — avoids protocol violations; client receives ack but message is silently discarded
- SslHandler extracted at `MqttSessionHandler.processConnect()` via `ctx.pipeline().get("ssl")` — null for plain TCP connections, non-null for TLS; threaded into `MqttConnectMsg`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Updated defaultConnectOptions() to include tbmq/tbmq credentials**
- **Found during:** Task 2 (integration tests)
- **Issue:** After wiring auth enforcement, existing tests using `defaultConnectOptions()` (no credentials) would fail with CONNACK NOT_AUTHORIZED. Anonymous is disabled by default.
- **Fix:** Added `opts.setUserName("tbmq")` and `opts.setPassword("tbmq".toCharArray())` to `defaultConnectOptions()` in `AbstractMqttIntegrationTest`.
- **Files modified:** `AbstractMqttIntegrationTest.java`
- **Verification:** All 113 tests pass
- **Committed in:** 4f078b3da (Task 2 commit)

**2. [Rule 1 - Bug] Updated raw MQTT packet builders to include tbmq/tbmq credentials**
- **Found during:** Task 2 (running full test suite)
- **Issue:** `MqttKeepAliveIntegrationTest.testKeepAliveExpiry_clientDisconnected` and two LWT tests build raw MQTT CONNECT packets without credentials. After auth enforcement, these packets are rejected with return code 0x05 instead of 0x00, causing assertion failures.
- **Fix:** Updated `buildMqttConnectPacket()` in `MqttKeepAliveIntegrationTest` and `buildMqttConnectWithWill()` in `MqttLwtIntegrationTest` to include username+password fields. Connect flags updated: 0x02→0xC2 (keepalive test), 0x06→0xC6 (LWT tests).
- **Files modified:** `MqttKeepAliveIntegrationTest.java`, `MqttLwtIntegrationTest.java`
- **Verification:** 3 previously failing tests now pass
- **Committed in:** 4f078b3da (Task 2 commit)

---

**Total deviations:** 2 auto-fixed (2 Rule 1 bugs)
**Impact on plan:** Both fixes required for correctness — auth enforcement breaks existing tests that assumed anonymous access. No scope creep.

## Issues Encountered

- IntelliJ IDEA's background Maven runner held RocksDB LOCK files on `tbmq-test-mqtt` path during test runs, causing `Resource temporarily unavailable` errors for subsequent test suite invocations. Resolved by waiting for IntelliJ's background tests to complete before running the final verification run. Pre-existing environmental issue unrelated to code changes.

## Known Stubs

None. Auth and ACL enforcement is fully wired and exercised by integration tests against real RocksDB-backed credentials.

## Next Phase Readiness

- Auth pipeline complete: valid credentials connect, invalid are rejected, ACL enforced on pub/sub
- `authRulePatterns` on `ClientSessionCtx` and `sslHandler` in `MqttConnectMsg` are in place for Phase 04-03 (TLS/mTLS listener)
- Denial counters active and ready for metrics verification in Phase 04-03

## Self-Check: PASSED

All files exist, commits verified, all 18 acceptance criteria pass.

---
*Phase: 04-security*
*Completed: 2026-04-08*
