---
status: partial
phase: 07-hardening-and-docker-release
source: [07-01-SUMMARY.md, 07-02-SUMMARY.md, 07-REVIEW-FIX.md]
started: 2026-04-11T20:00:00Z
updated: 2026-04-11T20:00:00Z
---

## Current Test

number: 1
name: Full Test Suite Regression
expected: |
  Run `mvn test -pl lightweight` from the project root. All tests pass (zero failures, zero errors). Soak test is NOT included in this run (excluded via excludedGroups). Expected: ~152+ tests run, 0 failures, 0 errors.
awaiting: user response

## Tests

### 1. Full Test Suite Regression
expected: Run `mvn test -pl lightweight`. All tests pass with zero failures and zero errors. Soak test is excluded from this run. Startup warning tests and metrics integration tests are included.
result: [pending]

### 2. Prometheus Metrics Integration
expected: Run `mvn test -pl lightweight -Dtest=BrokerMetricsIT`. All 5 tests pass: messages received counter > 0 after publish, messages delivered counter > 0 after subscriber receives, auth success counter > 0 after connect, auth failure counter > 0 after rejected connect, dispatch queue depth = 0 when idle.
result: [pending]

### 3. Soak Test Short Run
expected: Run `mvn test -pl lightweight -Dgroups=soak -Dsurefire.excludedGroups= -Dsoak.duration.minutes=1`. Soak test connects 500 clients, publishes messages for ~1 minute, asserts zero Netty ByteBuf leaks (PARANOID mode), and asserts JVM heap stability within 20% of baseline. Test passes.
result: [pending]

### 4. ARM64 Validation Script
expected: Run `bash -n scripts/arm64-validate.sh && test -x scripts/arm64-validate.sh && echo OK`. Output is "OK" — script has valid bash syntax and is executable. Script contains `set -euo pipefail`, `trap cleanup EXIT`, `mosquitto_pub`/`mosquitto_sub` commands, and `ARM64 validation PASSED` success message.
result: [pending]

### 5. Startup Warning Banner (Docker)
expected: Start the broker Docker container without TLS and without volume-mounting /data/rocksdb. In `docker logs`, see a bordered `=== TBMQ LIGHTWEIGHT — STARTUP WARNINGS ===` banner at WARN level containing: "TLS is not configured", "is NOT volume-mounted", and separately at INFO level: "Retained messages are stored in-memory only" (not inside the warning banner).
result: [pending]

### 6. Code Review Fix: Null Password Auth Rejection (WR-03)
expected: In `DefaultLightweightAuthService.java`, the `tryBasicAuth` method now explicitly rejects credentials with `password == null` — returns `AuthResult.failure()` instead of silently skipping the password check. Verify by reading the method: a `basicCreds.getPassword() == null` guard exists before the password comparison.
result: [pending]

### 7. Code Review Fix: Graceful Dispatcher Shutdown (WR-02)
expected: In `DefaultMsgDispatcherService.java`, the `stop()` method now uses two-phase shutdown: `consumerPool.shutdown()` followed by `awaitTermination(5, SECONDS)`, falling back to `shutdownNow()` only if the drain timeout expires. Verify by reading the stop() method.
result: [pending]

## Summary

total: 7
passed: 0
issues: 0
pending: 7
skipped: 0
blocked: 0

## Gaps
