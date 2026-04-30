# Requirements: TBMQ Lightweight

**Defined:** 2026-04-02
**Core Value:** A fully functional MQTT broker that starts with a single `docker run` command and requires zero external infrastructure

## v1 Requirements

Requirements for initial release (R1). Each maps to roadmap phases.

### Protocol

- [x] **PROTO-01**: Broker accepts MQTT 3.1.1 CONNECT packets and completes the connection handshake with CONNACK
- [x] **PROTO-02**: Broker handles PUBLISH/PUBACK/PUBREC/PUBREL/PUBCOMP for QoS 0, 1, and 2 message flows
- [x] **PROTO-03**: Broker processes SUBSCRIBE/SUBACK and UNSUBSCRIBE/UNSUBACK with correct return codes
- [x] **PROTO-04**: Broker responds to PINGREQ with PINGRESP and disconnects clients that exceed keep-alive timeout
- [x] **PROTO-05**: Broker stores retained messages in memory and delivers them to new subscribers on matching topics
- [x] **PROTO-06**: Broker publishes Last Will and Testament message when a client disconnects ungracefully or exceeds keep-alive
- [x] **PROTO-07**: Broker supports Clean Session flag — sessions are in-memory only and not persisted across broker restarts
- [x] **PROTO-08**: Broker accepts MQTT 5.0 CONNECT packets and negotiates protocol version with 3.1.1 clients
- [x] **PROTO-09**: Broker handles MQTT 5.0 properties: session expiry interval, user properties, reason codes, and topic aliases
- [x] **PROTO-10**: Broker supports MQTT 5.0 shared subscriptions ($share/group/topic) for load-balanced message delivery
- [x] **PROTO-11**: Broker correctly handles client takeover (new connection with same client ID replaces existing session) including LWT behavior

### Transport

- [x] **TRAN-01**: Broker listens on configurable TCP port (default 1883) for plain MQTT connections
- [x] **TRAN-02**: Broker supports TLS-encrypted MQTT connections via mounted server certificate and key files
- [x] **TRAN-03**: Broker accepts MQTT over WebSocket connections on configurable port (ws://)
- [x] **TRAN-04**: Broker accepts MQTT over secure WebSocket connections (wss://) with correct subprotocol header handling
- [x] **TRAN-05**: Broker echoes `Sec-WebSocket-Protocol: mqtt` header in WebSocket handshake response for browser client compatibility

### Authentication & Authorization

- [x] **AUTH-01**: Broker authenticates clients via username/password credentials stored in RocksDB
- [x] **AUTH-02**: Broker authenticates clients via X.509 client certificates during mutual TLS handshake
- [x] **AUTH-03**: Broker enforces topic-level ACL rules (publish and subscribe permissions) stored in RocksDB
- [x] **AUTH-04**: Broker requires authentication by default — anonymous access is disabled unless explicitly enabled via configuration
- [x] **AUTH-05**: Credentials and ACL rules persist across broker restarts via RocksDB storage

### Infrastructure

- [x] **INFR-01**: Broker uses in-process message dispatch (queue-based) to route messages between publisher and subscriber sessions, replacing Kafka
- [x] **INFR-02**: Broker uses RocksDB as embedded key-value store for durable state (credentials, ACLs), replacing PostgreSQL
- [x] **INFR-03**: Broker uses in-process cache (Caffeine) for hot-path lookups (session state, subscription matching), replacing Redis/Valkey
- [x] **INFR-04**: Message dispatch interface is abstracted to allow swapping queue implementations without refactoring consumers

### Operations

- [x] **OPS-01**: Broker exposes Prometheus-compatible `/metrics` endpoint with connection counts, message rates, and error counters
- [x] **OPS-02**: Broker ships as a single Docker image that starts with `docker run -p 1883:1883 thingsboard/tbmq-lightweight` with no external dependencies
- [x] **OPS-03**: Docker image supports both linux/amd64 and linux/arm64 architectures
- [x] **OPS-04**: Broker persists RocksDB data to a volume-mountable path so configuration survives container restarts
- [x] **OPS-05**: Broker starts and accepts connections within seconds on a standard developer machine
- [x] **OPS-06**: Broker shuts down gracefully — disconnects active clients, flushes RocksDB, releases native resources without JVM crash

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### Persistence

- **PERS-01**: Broker queues QoS 1/2 messages for disconnected clients and delivers on reconnect (persistent sessions)
- **PERS-02**: Broker persists subscription state across restarts (durable subscriptions)
- **PERS-03**: Broker persists retained messages to RocksDB so they survive restarts

### Management

- **MGMT-01**: Broker exposes REST API for managing credentials, ACLs, and broker state
- **MGMT-02**: Broker includes a web UI for monitoring connections, topics, and managing auth/ACL
- **MGMT-03**: Broker supports hot-reload of credentials and ACL changes without restart

### Integration

- **INTG-01**: Broker can forward MQTT telemetry to a connected ThingsBoard instance
- **INTG-02**: Broker supports rate limiting per client and per topic

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Clustering / horizontal scaling | Single-node by design; clustering is standard TBMQ's value proposition |
| Plugin / extension SDK | Premature API surface; auth/ACL configuration covers 95% of use cases |
| Rule engine / data transformation | Scope creep risk; belongs in application layer or enterprise TBMQ |
| MQTT-SN / CoAP / LwM2M gateways | Protocol gateway complexity disproportionate to R1 audience |
| Anonymous access by default | Security anti-pattern; Mosquitto's default open access is actively exploited |
| Persistent sessions in R1 | Substantial complexity (durable session store, message queuing, offset tracking); deferred to R2 |
| Web UI in R1 | Adds frontend build pipeline; contradicts zero-dependency value; deferred to R2+ |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| INFR-02 | Phase 1 | Complete |
| INFR-03 | Phase 1 | Complete |
| OPS-02 | Phase 1 | Complete |
| OPS-03 | Phase 1 | Complete |
| OPS-04 | Phase 1 | Complete |
| OPS-05 | Phase 1 | Complete |
| OPS-06 | Phase 1 | Complete |
| PROTO-01 | Phase 2 | Complete |
| PROTO-02 | Phase 2 | Complete |
| PROTO-03 | Phase 2 | Complete |
| PROTO-04 | Phase 2 | Complete |
| PROTO-05 | Phase 2 | Complete |
| PROTO-06 | Phase 2 | Complete |
| PROTO-07 | Phase 2 | Complete |
| PROTO-11 | Phase 2 | Complete |
| TRAN-01 | Phase 2 | Complete |
| INFR-01 | Phase 3 | Complete |
| INFR-04 | Phase 3 | Complete |
| AUTH-01 | Phase 4 | Complete |
| AUTH-02 | Phase 4 | Complete |
| AUTH-03 | Phase 4 | Complete |
| AUTH-04 | Phase 4 | Complete |
| AUTH-05 | Phase 4 | Complete |
| TRAN-02 | Phase 4 | Complete |
| TRAN-03 | Phase 5 | Complete |
| TRAN-04 | Phase 5 | Complete |
| TRAN-05 | Phase 5 | Complete |
| PROTO-08 | Phase 6 | Complete |
| PROTO-09 | Phase 6 | Complete |
| PROTO-10 | Phase 6 | Complete |
| OPS-01 | Phase 7 | Complete |

**Coverage:**
- v1 requirements: 31 total
- Mapped to phases: 31
- Unmapped: 0

---
*Requirements defined: 2026-04-02*
*Last updated: 2026-04-30 — ticked PROTO-08/09/10 (Phase 6) and OPS-01 (Phase 7) after verification*
