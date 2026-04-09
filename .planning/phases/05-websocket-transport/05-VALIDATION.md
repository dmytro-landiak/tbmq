---
phase: 5
slug: websocket-transport
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-09
---

# Phase 5 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test |
| **Config file** | `lightweight/pom.xml` (Surefire 3.2.5 with `reuseForks=true`) |
| **Quick run command** | `cd lightweight && mvn test -Dtest=MqttWsIntegrationTest,MqttWssIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` |
| **Full suite command** | `cd lightweight && mvn test` |
| **Estimated runtime** | ~30 seconds |

---

## Sampling Rate

- **After every task commit:** Run `cd lightweight && mvn test -Dtest=MqttWsIntegrationTest,MqttWssIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
- **After every plan wave:** Run `cd lightweight && mvn test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 05-01-01 | 01 | 1 | TRAN-03 | integration | `mvn test -Dtest=MqttWsIntegrationTest` | ❌ W0 | ⬜ pending |
| 05-01-02 | 01 | 1 | TRAN-04 | integration | `mvn test -Dtest=MqttWssIntegrationTest` | ❌ W0 | ⬜ pending |
| 05-01-03 | 01 | 1 | TRAN-05 | integration | `mvn test -Dtest=MqttWsIntegrationTest` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `lightweight/src/test/java/.../mqtt/MqttWsIntegrationTest.java` — covers TRAN-03 and TRAN-05
- [ ] `lightweight/src/test/java/.../mqtt/MqttWssIntegrationTest.java` — covers TRAN-04

*Existing `AbstractMqttIntegrationTest` base, Paho client, and test cert resources from Phase 4 are reused.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Browser MQTT.js client connects with `Sec-WebSocket-Protocol: mqtt` | TRAN-05 | Browser-specific header check | 1. Open browser dev tools 2. Connect MQTT.js to `ws://host:port/mqtt` 3. Verify response header in Network tab |

*Note: TRAN-05 is also verified automatically — Paho's `WebSocketHandshake` validates the server's `Sec-WebSocket-Protocol` response header. If absent or mismatched, Paho throws `HandshakeFailedException`.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
