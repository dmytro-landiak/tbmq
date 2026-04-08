---
phase: 04
slug: security
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-08
---

# Phase 04 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Paho MQTT Client |
| **Config file** | `lightweight/pom.xml` (Surefire plugin) |
| **Quick run command** | `mvn -pl lightweight test -Dtest="{TestClass}" -q` |
| **Full suite command** | `mvn -pl lightweight test -q` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn -pl lightweight compile -q`
- **After every plan wave:** Run `mvn -pl lightweight test -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 04-01-01 | 01 | 1 | AUTH-01, AUTH-05 | unit | `mvn -pl lightweight test -Dtest="CredentialServiceTest" -q` | ❌ W0 | ⬜ pending |
| 04-01-02 | 01 | 1 | AUTH-03, AUTH-05 | unit | `mvn -pl lightweight test -Dtest="AclServiceTest" -q` | ❌ W0 | ⬜ pending |
| 04-02-01 | 02 | 2 | AUTH-01, AUTH-04 | integration | `mvn -pl lightweight test -Dtest="MqttAuthIntegrationTest" -q` | ❌ W0 | ⬜ pending |
| 04-02-02 | 02 | 2 | AUTH-03 | integration | `mvn -pl lightweight test -Dtest="MqttAclIntegrationTest" -q` | ❌ W0 | ⬜ pending |
| 04-03-01 | 03 | 3 | TRAN-02 | integration | `mvn -pl lightweight test -Dtest="MqttTlsIntegrationTest" -q` | ❌ W0 | ⬜ pending |
| 04-03-02 | 03 | 3 | AUTH-02 | integration | `mvn -pl lightweight test -Dtest="MqttMtlsIntegrationTest" -q` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] Test PEM certificates in `lightweight/src/test/resources/tls/` — server cert, server key, CA cert, client cert, client key
- [ ] `MqttAuthIntegrationTest.java` — stubs for AUTH-01, AUTH-04
- [ ] `MqttAclIntegrationTest.java` — stubs for AUTH-03
- [ ] `MqttTlsIntegrationTest.java` — stubs for TRAN-02
- [ ] `MqttMtlsIntegrationTest.java` — stubs for AUTH-02

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Credentials survive Docker restart | AUTH-05 | Requires Docker volume mount lifecycle | 1. Run broker with volume mount 2. Add credential 3. Restart container 4. Verify credential still works |
| TLS with real CA-signed cert | TRAN-02 | Integration tests use self-signed certs | Manual test with Let's Encrypt or other CA |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
