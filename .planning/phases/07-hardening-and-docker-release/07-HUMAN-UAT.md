---
status: partial
phase: 07-hardening-and-docker-release
source: [07-VERIFICATION.md]
started: 2026-04-11T19:00:00Z
updated: 2026-04-30T13:45:00Z
---

## Current Test

ARM64 hardware validation (test 1) is the only remaining HUMAN-UAT item.

## Tests

### 1. ARM64 Hardware Validation
expected: Run `./scripts/arm64-validate.sh [image:tag]` on a real ARM64 device (Raspberry Pi 4/5, macOS M-series, or AWS Graviton). Broker starts, accepts MQTT connections, publishes/receives messages, and RocksDB data survives container restart. All 5 validation steps pass.
result: [pending]

### 2. Startup Warning Banner in Docker Logs
expected: The `=== TBMQ LIGHTWEIGHT — STARTUP WARNINGS ===` banner appears in `docker logs` output when container started without TLS configured and without volume-mounted `/data/rocksdb`.
result: passed (2026-04-30 — user confirmed banner observed in real `docker logs` output)

## Summary

total: 2
passed: 1
issues: 0
pending: 1
skipped: 0
blocked: 0

## Gaps
