# Phase 6: MQTT 5.0 - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-11
**Phase:** 06-mqtt-5-0
**Areas discussed:** Session expiry scope, Properties coverage, Shared subscription routing, Topic alias defaults

---

## Session Expiry Scope

| Option | Description | Selected |
|--------|-------------|----------|
| Accept but treat as 0 | Accept property in CONNECT, behave as expiry=0, return enforced value in CONNACK | ✓ |
| Accept and hold in-memory briefly | Keep session in memory for requested duration after disconnect | |
| Reject non-zero expiry | Send CONNACK with reason code if client requests non-zero | |

**User's choice:** Accept but treat as 0
**Notes:** Spec-compliant — broker is allowed to override session expiry

| Option | Description | Selected |
|--------|-------------|----------|
| Only when overriding | Include Session Expiry Interval = 0 in CONNACK only when client requested non-zero | ✓ |
| Always advertise 0 | Always include in every CONNACK | |

**User's choice:** Only when overriding
**Notes:** Quiet when clients already expect ephemeral sessions

| Option | Description | Selected |
|--------|-------------|----------|
| Ignore — always 0 | DISCONNECT expiry also treated as 0, session cleaned up immediately | ✓ |
| Log but ignore | Same behavior but log at DEBUG level | |

**User's choice:** Ignore — always 0
**Notes:** Consistent with CONNECT override

---

## Properties Coverage

| Option | Description | Selected |
|--------|-------------|----------|
| User Properties | Key-value metadata forwarded through pipeline | ✓ |
| Message Expiry Interval | TTL for published/retained messages | ✓ |
| Receive Maximum | Per-session in-flight QoS 1/2 limit | ✓ |
| Payload Format + Content Type | Pass-through on PUBLISH | ✓ |

**User's choice:** All four properties fully supported
**Notes:** Comprehensive MQTT 5.0 property coverage for R1

| Option | Description | Selected |
|--------|-------------|----------|
| Response Topic + Correlation Data: pass-through | Forward without interpretation, enables request-response | ✓ |
| Response Topic + Correlation Data: ignore | Strip properties | |

**User's choice:** Pass-through
**Notes:** Zero broker logic, just preserve in message pipeline

| Option | Description | Selected |
|--------|-------------|----------|
| Support Subscription Identifiers | Client assigns ID, broker includes in delivered messages | ✓ |
| Decline support | Set available = 0 in CONNACK | |

**User's choice:** Support
**Notes:** Enables client-side message routing

| Option | Description | Selected |
|--------|-------------|----------|
| Defer Enhanced Auth to R2 | R1 has username/password + X.509 mTLS | ✓ |
| Support SCRAM-SHA-256 | Full SCRAM handshake | |

**User's choice:** Defer to R2
**Notes:** Existing auth mechanisms sufficient for R1

| Option | Description | Selected |
|--------|-------------|----------|
| Full reason codes everywhere | Every ACK/response uses proper MQTT 5.0 reason codes | ✓ |
| CONNACK and DISCONNECT only | Focus on connection lifecycle | |

**User's choice:** Full reason codes everywhere
**Notes:** What the spec expects and what MQTT 5.0 clients depend on

---

## Shared Subscription Routing

| Option | Description | Selected |
|--------|-------------|----------|
| Round-robin | Rotate through group members sequentially, AtomicInteger counter | ✓ |
| Random | Random group member per message | |
| Sticky (hash-based) | Hash topic name to consistent member | |

**User's choice:** Round-robin
**Notes:** Predictable, even distribution, simple implementation

| Option | Description | Selected |
|--------|-------------|----------|
| Parse prefix, store in trie | Strip $share/group/, store actual filter with group annotation | ✓ |
| Separate shared subscription registry | Parallel data structure for shared subs | |

**User's choice:** Parse prefix, store in trie
**Notes:** Trie matching works unchanged; resolve at delivery time

| Option | Description | Selected |
|--------|-------------|----------|
| 5.0 only | Restrict shared subs to MQTT 5.0 clients | |
| Allow for both versions | Both 3.1.1 and 5.0 can use shared subscriptions | ✓ |

**User's choice:** Allow for both versions
**Notes:** Matches upstream TBMQ behavior

---

## Topic Alias Defaults

| Option | Description | Selected |
|--------|-------------|----------|
| Bidirectional | Client-to-broker AND broker-to-client aliases | ✓ |
| Client-to-broker only | Broker resolves but never allocates | |
| Disable topic aliases | Topic Alias Maximum = 0 | |

**User's choice:** Bidirectional
**Notes:** Full MQTT 5.0 compliance, port upstream TopicAliasCtx

| Option | Description | Selected |
|--------|-------------|----------|
| 10 (default) | Matches upstream TBMQ, configurable via env var | ✓ |
| 0 (disabled by default) | Opt-in only | |
| 65535 (spec max) | Very permissive | |

**User's choice:** 10
**Notes:** Configurable via TBMQ_TOPIC_ALIAS_MAX

| Option | Description | Selected |
|--------|-------------|----------|
| Minimum length threshold | Only alias topics longer than N characters | ✓ |
| Alias all topics | Alias regardless of length | |

**User's choice:** Minimum length threshold
**Notes:** Avoids wasting alias slots on short topics

---

## Claude's Discretion

- MqttPropertiesUtil implementation details
- Reason code resolver design
- TopicAliasCtx minimum length threshold default
- Receive Maximum default value
- Message Expiry tracking approach
- Subscription Identifier propagation mechanism
- Version-aware branching patterns

## Deferred Ideas

- Enhanced Authentication (SCRAM/AUTH packets) — deferred to R2
