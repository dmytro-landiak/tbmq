---
phase: 7
slug: hardening-and-docker-release
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-11
---

# Phase 7 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Paho/HiveMQ MQTT clients |
| **Config file** | `lightweight/pom.xml` (Surefire plugin) |
| **Quick run command** | `cd lightweight && mvn test -pl . -Dtest=BrokerMetricsServiceTest,StartupWarningServiceTest -q` |
| **Full suite command** | `cd lightweight && mvn verify -pl . -q` |
| **Estimated runtime** | ~60 seconds (quick), ~300 seconds (full) |

---

## Sampling Rate

- **After every task commit:** Run quick test command
- **After every plan wave:** Run full suite command
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| TBD | TBD | TBD | OPS-01 | — | N/A | integration | `mvn verify` | TBD | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] Verify existing test infrastructure compiles and passes
- [ ] Confirm Paho/HiveMQ test clients available in test scope

*Existing infrastructure covers most phase requirements.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| ARM64 Docker validation | SC-3 | Requires real ARM64 hardware | Run `scripts/arm64-validate.sh` on ARM64 device |
| 24-hour soak test | SC-2 | Duration exceeds CI time limits | Run soak test with `SOAK_DURATION_HOURS=24` on dedicated machine |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
