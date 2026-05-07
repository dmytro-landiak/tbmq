---
status: complete
phase: 07-hardening-and-docker-release
source: [07-VERIFICATION.md]
started: 2026-04-11T19:00:00Z
updated: 2026-05-07
---

## Current Test

All HUMAN-UAT items complete.

## Tests

### 1. ARM64 Hardware Validation
expected: Run `./scripts/arm64-validate.sh [image:tag]` on a real ARM64 device (Raspberry Pi 4/5, macOS M-series, or AWS Graviton). Broker starts, accepts MQTT connections, publishes/receives messages, and RocksDB data survives container restart. All 5 validation steps pass.
result: passed (2026-05-07 — executed on AWS EC2 t4g.small Graviton (Ubuntu 26.04 ARM64) using image `dlandiak2110/tbmq-lightweight:latest`. All 4 SC-3 checks confirmed: arm64/linux verified via `docker inspect`; broker logged `Started TbmqLightweightApplication` with no `UnsatisfiedLinkError` (RocksDB JNI on glibc/aarch64 OK); mosquitto pub/sub round-trip succeeded on port 1883; default `tbmq/tbmq` credentials persisted across container restart with named volume.)

### 2. Startup Warning Banner in Docker Logs
expected: The `=== TBMQ LIGHTWEIGHT — STARTUP WARNINGS ===` banner appears in `docker logs` output when container started without TLS configured and without volume-mounted `/data/rocksdb`.
result: passed (2026-04-30 — user confirmed banner observed in real `docker logs` output)

## Summary

total: 2
passed: 2
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
