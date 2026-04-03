---
phase: 1
slug: foundation
status: draft
nyquist_compliant: false
wave_0_complete: false
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

## Sampling Rate

- **After every task commit:** Run `mvn test -Dtest="RocksDbStorageTest,CacheConfigurationTest" -q`
- **After every plan wave:** Run `mvn verify -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 15 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 01-01-01 | 01 | 0 | INFR-02 | unit | `mvn test -Dtest="RocksDbStorageTest" -q` | ❌ W0 | ⬜ pending |
| 01-01-02 | 01 | 0 | INFR-03 | unit | `mvn test -Dtest="CacheConfigurationTest" -q` | ❌ W0 | ⬜ pending |
| 01-01-03 | 01 | 0 | OPS-02, OPS-05 | integration | `mvn test -Dtest="ApplicationStartupTest" -q` | ❌ W0 | ⬜ pending |
| 01-01-04 | 01 | 0 | OPS-06 | integration | `mvn test -Dtest="GracefulShutdownTest" -q` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/org/thingsboard/mqtt/broker/lightweight/storage/RocksDbStorageTest.java` — stubs for INFR-02, OPS-04
- [ ] `src/test/java/org/thingsboard/mqtt/broker/lightweight/cache/CacheConfigurationTest.java` — stubs for INFR-03
- [ ] `src/test/java/org/thingsboard/mqtt/broker/lightweight/ApplicationStartupTest.java` — stubs for OPS-02, OPS-05
- [ ] `src/test/java/org/thingsboard/mqtt/broker/lightweight/GracefulShutdownTest.java` — stubs for OPS-06
- [ ] `pom.xml` with JUnit 5 via `spring-boot-starter-test` + Surefire plugin

*Framework: `spring-boot-starter-test` includes JUnit 5, Mockito, AssertJ — no additional install.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Docker multi-arch build succeeds | OPS-03 | Requires Docker buildx with multi-platform builder | `docker buildx build --platform linux/amd64,linux/arm64 -t thingsboard/tbmq-lightweight .` |
| ARM64 RocksDB JNI loads | OPS-03 | Requires real ARM64 hardware or QEMU | Run image on ARM64 device, verify no `UnsatisfiedLinkError` |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 15s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
