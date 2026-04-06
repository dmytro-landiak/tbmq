---
phase: 2
slug: core-protocol-mqtt-3-1-1
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-06
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (via `spring-boot-starter-test`) + AssertJ |
| **Config file** | `lightweight/pom.xml` — Surefire 3.2.5 already configured |
| **Quick run command** | `mvn -f lightweight/pom.xml test -pl . -Dtest="*Test" -q` |
| **Full suite command** | `mvn -f lightweight/pom.xml test` |
| **Estimated runtime** | ~30 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn -f lightweight/pom.xml test -pl . -Dtest="*Test" -q`
- **After every plan wave:** Run `mvn -f lightweight/pom.xml test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 02-01-01 | 01 | 0 | PROTO-01 | integration | `mvn test -Dtest=MqttConnectIntegrationTest -pl lightweight` | ❌ W0 | ⬜ pending |
| 02-01-02 | 01 | 0 | PROTO-02 | integration | `mvn test -Dtest=MqttQosIntegrationTest -pl lightweight` | ❌ W0 | ⬜ pending |
| 02-01-03 | 01 | 0 | PROTO-03 | integration | `mvn test -Dtest=MqttSubscribeIntegrationTest -pl lightweight` | ❌ W0 | ⬜ pending |
| 02-01-04 | 01 | 0 | PROTO-04 | integration | `mvn test -Dtest=MqttKeepAliveIntegrationTest -pl lightweight` | ❌ W0 | ⬜ pending |
| 02-01-05 | 01 | 0 | PROTO-05 | integration | `mvn test -Dtest=MqttRetainedMsgIntegrationTest -pl lightweight` | ❌ W0 | ⬜ pending |
| 02-01-06 | 01 | 0 | PROTO-06 | integration | `mvn test -Dtest=MqttLwtIntegrationTest -pl lightweight` | ❌ W0 | ⬜ pending |
| 02-01-07 | 01 | 0 | PROTO-07 | integration | `mvn test -Dtest=MqttSessionIntegrationTest -pl lightweight` | ❌ W0 | ⬜ pending |
| 02-01-08 | 01 | 0 | PROTO-11 | integration | `mvn test -Dtest=MqttClientTakeoverTest -pl lightweight` | ❌ W0 | ⬜ pending |
| 02-01-09 | 01 | 0 | TRAN-01 | integration | `mvn test -Dtest=NettyServerBootstrapTest` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] Add Eclipse Paho MQTTv3 dependency (test scope) to `lightweight/pom.xml`
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/AbstractMqttIntegrationTest.java` — shared `@SpringBootTest` base class with `port=0`, Paho client factory, auto-connect/disconnect lifecycle
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttConnectIntegrationTest.java` — stubs for PROTO-01
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttQosIntegrationTest.java` — stubs for PROTO-02
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttSubscribeIntegrationTest.java` — stubs for PROTO-03
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttKeepAliveIntegrationTest.java` — stubs for PROTO-04
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttRetainedMsgIntegrationTest.java` — stubs for PROTO-05
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttLwtIntegrationTest.java` — stubs for PROTO-06
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttSessionIntegrationTest.java` — stubs for PROTO-07
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttClientTakeoverTest.java` — stubs for PROTO-11

---

## Manual-Only Verifications

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
