# TBMQ Lightweight

## What This Is

TBMQ Lightweight is a stripped-down, single-node MQTT broker that eliminates all external infrastructure dependencies — no Kafka, no PostgreSQL, no Redis/Valkey. It ships as a single Docker image and covers the core MQTT protocol surface (3.1.1 and 5.0) with embedded storage and in-process messaging, targeting developers evaluating TBMQ, small-scale production deployments, and edge environments where operational simplicity matters more than horizontal scalability.

## Core Value

A fully functional MQTT broker that starts with a single `docker run` command and requires zero external infrastructure — making TBMQ accessible for evaluation and small-scale production without the deployment complexity of the standard version.

## Requirements

### Validated

- [x] RocksDB embedded storage replacing PostgreSQL for durable state (credentials, ACLs) — Validated in Phase 1: Foundation
- [x] In-process cache replacing Redis/Valkey for coordination primitives — Validated in Phase 1: Foundation (Caffeine)
- [x] Basic Prometheus-compatible metrics endpoint — Validated in Phase 1: Foundation
- [x] Single Docker image deployment (`docker run -p 1883:1883 thingsboard/tbmq-lightweight`) — Validated in Phase 1: Foundation
- [x] Full MQTT 3.1.1 protocol support (CONNECT, PUBLISH, SUBSCRIBE, UNSUBSCRIBE, PING, DISCONNECT with QoS 0/1/2) — Validated in Phase 2: Core Protocol
- [x] In-memory session state for active client sessions — Validated in Phase 2: Core Protocol
- [x] Non-persistent messaging to currently connected subscribers (Clean Session only in R1) — Validated in Phase 2: Core Protocol
- [x] Retained messages stored in memory for broker lifetime — Validated in Phase 2: Core Protocol
- [x] Last Will and Testament (LWT) support — Validated in Phase 2: Core Protocol

### Active

- [ ] Full MQTT 5.0 protocol support (session expiry, user properties, reason codes, topic aliases, shared subscriptions)
- [x] TLS termination via mounted certificates — Validated in Phase 4: Security
- [ ] MQTT over WebSocket (ws:// and wss://)
- [x] Username/password and X.509 certificate-based authentication backed by RocksDB — Validated in Phase 4: Security
- [x] Topic-level ACL authorization stored in RocksDB — Validated in Phase 4: Security
- [x] In-process message dispatch (BlockingQueue) replacing Kafka — Validated in Phase 3: Message Dispatch

### Out of Scope

- Persistent sessions / offline message queuing — deferred to R2
- Durable subscriptions across broker restarts — deferred to R2
- Clustering / horizontal scaling — single node only; requires standard TBMQ
- ThingsBoard PE/CE integration — deferred; standalone broker in R1
- Web UI — deferred to future release
- Rate limiting — deferred to future release
- WSS load balancing — not applicable without clustering

## Context

- **Ecosystem**: Built on the same MQTT engine as enterprise-grade TBMQ, enabling migration path from lightweight to clustered deployment
- **Competitive landscape**: Mosquitto (zero-dep, no enterprise features), EMQX Community (strong but feature-gated), HiveMQ Community (25 connection limit), NanoMQ (C-based, embedded focus)
- **Repository strategy**: Separate repository preferred over configurable mode in existing TBMQ codebase — infrastructure layers are different enough that co-habitation creates more long-term cost than it saves
- **Existing codebase**: The current TBMQ repo contains shared protocol logic (MQTT parsing, state machines) that may be extracted to a common library if duplication becomes a concern
- **Target scale**: Tens of thousands of concurrent connections on a single node
- **Storage approach**: RocksDB via JNI for durable state (credentials, ACLs, retained messages); already used in broader ThingsBoard ecosystem (TB PE queue configurations)
- **Message routing**: In-process dispatch layer (Disruptor ring buffer or Java BlockingQueue) replacing Kafka for inter-session message routing

## Constraints

- **Single node**: No clustering, no distributed coordination — simplifies architecture but limits scale
- **No persistence across restarts (R1)**: Sessions and retained messages are in-memory only; only credentials and ACLs survive restarts via RocksDB
- **JVM-based**: Must run on Java 17+; RocksDB JNI adds platform-specific native library requirements
- **Multi-arch Docker**: Must support both x86 and ARM architectures for edge deployments
- **Separate repository**: Preferred approach — keeps standard TBMQ clean, independent release cycle

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Separate repository over configurable mode | Infrastructure layers differ enough that dual-mode branching in main repo creates long-term maintenance cost | — Pending |
| RocksDB for embedded storage | Battle-tested for embedded KV workloads, already in ThingsBoard ecosystem, small disk footprint | Validated — Phase 1 |
| In-process message dispatch over Kafka | Single-node deployment removes need for distributed messaging; in-process queue provides lower latency | Validated — Phase 3 |
| Clean Session only in R1 | Persistent sessions add significant complexity; defer to R2 to ship core broker faster | Validated — Phase 2 |
| No Web UI in R1 | Focus on broker core; UI adds frontend build complexity without core value for evaluation use case | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd:transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-09 after Phase 4 Security (auth, ACL, TLS, mTLS) completion*
