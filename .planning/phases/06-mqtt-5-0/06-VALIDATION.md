---
phase: 6
slug: mqtt-5-0
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-11
---

# Phase 6 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test (existing) |
| **Config file** | `lightweight/pom.xml` Surefire plugin |
| **Quick run command** | `mvn -pl lightweight test -Dtest=Mqtt5*Test -f /home/dlandiak/projects/gsd/tbmq/pom.xml` |
| **Full suite command** | `mvn -pl lightweight test -f /home/dlandiak/projects/gsd/tbmq/pom.xml` |
| **Estimated runtime** | ~30 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn -pl lightweight test -Dtest=Mqtt5*Test -f /home/dlandiak/projects/gsd/tbmq/pom.xml`
- **After every plan wave:** Run `mvn -pl lightweight test -f /home/dlandiak/projects/gsd/tbmq/pom.xml`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 06-01-01 | 01 | 0 | PROTO-08 | — | N/A (test infra) | setup | `mvn -pl lightweight test-compile -f .../pom.xml` | Wave 0 | pending |
| 06-02-01 | 02 | 1 | PROTO-08 | T-06-01 | Version detection rejects unknown protocol levels | integration | `mvn -pl lightweight test -Dtest=Mqtt5VersionNegotiationTest -f .../pom.xml` | Wave 0 | pending |
| 06-02-02 | 02 | 1 | PROTO-09 | T-06-02 | Session expiry always overridden to 0 | integration | `mvn -pl lightweight test -Dtest=Mqtt5PropertiesTest -f .../pom.xml` | Wave 0 | pending |
| 06-03-01 | 03 | 1 | PROTO-09 | T-06-03 | Topic alias bounds checked; invalid alias disconnects client | integration | `mvn -pl lightweight test -Dtest=Mqtt5TopicAliasTest -f .../pom.xml` | Wave 0 | pending |
| 06-03-02 | 03 | 1 | PROTO-09 | — | Reason codes correct for all ACK types | integration | `mvn -pl lightweight test -Dtest=Mqtt5ReasonCodeTest -f .../pom.xml` | Wave 0 | pending |
| 06-04-01 | 04 | 2 | PROTO-10 | T-06-04 | Malformed $share topic results in DISCONNECT, not NPE | integration | `mvn -pl lightweight test -Dtest=Mqtt5SharedSubscriptionTest -f .../pom.xml` | Wave 0 | pending |

*Status: pending · green · red · flaky*

---

## Wave 0 Requirements

- [ ] `lightweight/pom.xml` — add `org.eclipse.paho.mqttv5.client` test dependency
- [ ] `lightweight/src/test/java/.../mqtt/Mqtt5VersionNegotiationTest.java` — stubs for PROTO-08
- [ ] `lightweight/src/test/java/.../mqtt/Mqtt5PropertiesTest.java` — stubs for PROTO-09
- [ ] `lightweight/src/test/java/.../mqtt/Mqtt5TopicAliasTest.java` — stubs for PROTO-09
- [ ] `lightweight/src/test/java/.../mqtt/Mqtt5ReasonCodeTest.java` — stubs for PROTO-08/09
- [ ] `lightweight/src/test/java/.../mqtt/Mqtt5SharedSubscriptionTest.java` — stubs for PROTO-10

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Bidirectional topic alias performance under load | PROTO-09 | Requires sustained concurrent connections | Connect 100 v5 clients, each publishing to unique topics; verify alias allocation and resolution |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
