---
status: complete
phase: 07-hardening-and-docker-release
source: [07-01-SUMMARY.md, 07-02-SUMMARY.md, 07-REVIEW-FIX.md]
started: 2026-04-11T20:00:00Z
updated: 2026-04-16T15:55:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Full Test Suite Regression
expected: Run `mvn test` in lightweight directory. All tests pass with zero failures and zero errors. Soak test is excluded from this run.
result: pass

### 2. Prometheus Metrics Integration
expected: Run `mvn test -Dtest=BrokerMetricsIT`. All 5 tests pass: messages received counter, messages delivered counter, auth success counter, auth failure counter, dispatch queue depth.
result: pass

### 3. Soak Test Short Run
expected: Run `mvn test -Dgroups=soak -Dsurefire.excludedGroups= -Dsoak.duration.minutes=1`. Soak test connects 500 clients, publishes for ~1 minute, asserts zero Netty ByteBuf leaks (PARANOID mode) and JVM heap stability within 20%.
result: pass

### 4. ARM64 Validation Script
expected: Run `bash -n scripts/arm64-validate.sh && test -x scripts/arm64-validate.sh && echo OK`. Output is "OK" — script has valid bash syntax and is executable.
result: pass

### 5. Startup Warning Banner (Docker)
expected: Start the broker without TLS and without volume-mounting /data/rocksdb. Logs show a bordered TBMQ LIGHTWEIGHT — STARTUP WARNINGS banner at WARN level with "TLS is not configured" and separately at INFO level "Retained messages are stored in-memory only".
result: pass

### 6. Code Review Fix: Null Password Auth Rejection (WR-03)
expected: In DefaultLightweightAuthService.java, tryBasicAuth method rejects credentials with password == null — returns AuthResult.failure() before password comparison.
result: pass

### 7. Code Review Fix: Graceful Dispatcher Shutdown (WR-02)
expected: In DefaultMsgDispatcherService.java, stop() uses two-phase shutdown: consumerPool.shutdown() followed by awaitTermination(5s), falling back to shutdownNow() only on timeout.
result: pass

## Summary

total: 7
passed: 7
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
