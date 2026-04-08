# Phase 4: Security - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md -- this log preserves the alternatives considered.

**Date:** 2026-04-08
**Phase:** 04-security
**Areas discussed:** Credential Storage Format, Authentication Pipeline, ACL Rule Design, TLS Configuration, Anonymous Access Control
**Mode:** Auto (--auto flag, all decisions auto-selected)

---

## Credential Storage Format

| Option | Description | Selected |
|--------|-------------|----------|
| JSON serialization | Human-readable, matches Phase 1 D-05, Jackson on classpath | auto |
| Protobuf binary | Compact, fast, but adds complexity for debugging | |
| Custom binary | Smallest footprint, hardest to debug | |

**User's choice:** JSON serialization (auto-selected: recommended default)
**Notes:** Consistent with Phase 1 decision D-05 that established JSON for all RocksDB values.

---

## Authentication Pipeline

| Option | Description | Selected |
|--------|-------------|----------|
| Actor-level auth in processConnect() | Matches TBMQ pattern, keeps Netty pipeline simple | auto |
| Netty pipeline handler (pre-actor) | Rejects before actor creation, but complicates pipeline | |
| Spring Security filter chain | Not applicable to raw Netty MQTT connections | |

**User's choice:** Actor-level authentication (auto-selected: recommended default)
**Notes:** TBMQ validates credentials during CONNECT message processing in the actor. Lightweight follows the same pattern for consistency.

---

## ACL Rule Design

| Option | Description | Selected |
|--------|-------------|----------|
| Regex-based topic patterns, deny-by-default | Matches TBMQ AuthorizationRuleService, flexible | auto |
| Simple prefix matching, allow-by-default | Simpler but less secure | |
| Per-topic explicit allow/deny lists | Fine-grained but verbose to configure | |

**User's choice:** Regex-based topic patterns with deny-by-default (auto-selected: recommended default)
**Notes:** Matches TBMQ's existing AuthorizationRuleService implementation. Regex patterns support wildcards and complex topic hierarchies.

---

## TLS Configuration

| Option | Description | Selected |
|--------|-------------|----------|
| PEM files via Docker volume mount | Simplest for Docker/edge, matches TBMQ PemSslCredentials | auto |
| Java keystore (JKS/PKCS12) | Standard JVM approach, more complex for Docker users | |
| Both PEM and keystore | Maximum flexibility, more code to maintain | |

**User's choice:** PEM files (auto-selected: recommended default)
**Notes:** PEM is the standard for Docker-based deployments. TBMQ already has PemSslCredentials implementation to copy from.

---

## Anonymous Access Control

| Option | Description | Selected |
|--------|-------------|----------|
| Disabled by default, env var toggle | Security-first per AUTH-04, easy to enable for dev | auto |
| Enabled by default, env var to require auth | Convenient for eval but violates AUTH-04 | |
| No anonymous support at all | Too restrictive for development use | |

**User's choice:** Disabled by default with env var toggle (auto-selected: recommended default)
**Notes:** AUTH-04 explicitly requires authentication by default. `TBMQ_SECURITY_ANONYMOUS_ENABLED=true` enables anonymous for dev/eval.

---

## Claude's Discretion

- Exact RocksDB serialization format details (within JSON constraint)
- BCrypt strength parameter
- Credential service interface design
- ACL cache strategy (Caffeine)
- Integration test design
- TLS cipher suite defaults

## Deferred Ideas

- REST API for credential management (MGMT-01, R2)
- Web UI for auth config (MGMT-02, R2)
- Hot-reload of credentials (MGMT-03, R2)
- SCRAM enhanced auth (Phase 6 / MQTT 5.0)
