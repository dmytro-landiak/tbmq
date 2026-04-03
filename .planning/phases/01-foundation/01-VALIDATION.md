---
phase: 1
slug: foundation
status: draft
nyquist_compliant: true
wave_0_complete: true
created: 2026-04-03
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (managed by Spring Boot 3.5.3 via `spring-boot-starter-test`) |
| **Config file** | None — Wave 0 installs via `pom.xml` Surefire configuration |
| **Quick run command** | `mvn test -Dtest="RocksDbStorageTest,CacheConfigurationTest" -q` |
| **Full suite command** | `mvn verify -q` |
| **Estimated runtime** | ~15 seconds |

---

## Wave 0 Note

TDD tasks in Plans 01 and 02 write tests BEFORE implementation within the same task. This satisfies the Wave 0 requirement — test stubs exist and fail (RED) before production code is written (GREEN). A separate Wave 0 plan is not needed because the TDD discipline within each task provides equivalent coverage.

---

## Sampling Rate

- **After every task commit:** Run `mvn test -Dtest="RocksDbStorageTest,CacheConfigurationTest" -q`
- **After every plan wave:** Run `mvn verify -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 15 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | Status |
|---------|------|------|-------------|-----------|-------------------|--------|
| 01-01-01 | 01 | 1 | INFR-02 | unit (TDD) | `mvn test -Dtest="TbmqLightweightApplicationTest" -q` | pending |
| 01-01-02 | 01 | 1 | INFR-02, OPS-04 | unit (TDD) | `mvn test -Dtest="RocksDbStorageTest" -q` | pending |
| 01-02-01 | 02 | 2 | OPS-05 | integration (TDD) | `mvn test -Dtest="NettyServerBootstrapTest" -q` | pending |
| 01-02-02 | 02 | 2 | INFR-03, OPS-06 | integration | `mvn test -Dtest="CacheConfigurationTest,GracefulShutdownTest" -q` | pending |
| 01-03-01 | 03 | 3 | OPS-02, OPS-03 | manual | Docker build + run verification | pending |

*Status: pending / green / red / flaky*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Docker multi-arch build succeeds | OPS-03 | Requires Docker buildx with multi-platform builder | `docker buildx build --platform linux/amd64,linux/arm64 -t thingsboard/tbmq-lightweight .` |
| ARM64 RocksDB JNI loads | OPS-03 | Requires real ARM64 hardware or QEMU | Run image on ARM64 device, verify no `UnsatisfiedLinkError` |
| Container starts and accepts MQTT TCP | OPS-02 | End-to-end Docker verification | Plan 01-03, Task 2 (checkpoint:human-verify) |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or manual verification step
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] TDD within tasks satisfies Wave 0 (tests written before implementation)
- [x] No watch-mode flags
- [x] Feedback latency < 15s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
