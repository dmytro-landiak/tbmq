# Roadmap: TBMQ Lightweight

## Overview

TBMQ Lightweight is built bottom-up: infrastructure first, then protocol correctness, then performance-critical dispatch, then security, then transport extensions, then protocol version 5.0, then soak validation and Docker release. Each phase delivers a complete and testable capability. Nothing depends on something not yet built. The broker ships as a single Docker image with zero external dependencies when Phase 7 completes.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [ ] **Phase 1: Foundation** - Spring Boot app, RocksDB JNI, Netty TCP bootstrap, and multi-arch Docker image with Prometheus metrics
- [ ] **Phase 2: Core Protocol (MQTT 3.1.1)** - Full MQTT 3.1.1 broker over TCP: QoS 0/1/2, retained messages, LWT, keep-alive, and client takeover
- [ ] **Phase 3: Message Dispatch** - In-process publish routing via abstracted dispatch interface; wildcard subscription trie; non-blocking Netty handoff
- [ ] **Phase 4: Security** - TLS termination, username/password auth and X.509 mTLS backed by RocksDB, topic-level ACL, secure-by-default configuration
- [ ] **Phase 5: WebSocket Transport** - MQTT over ws:// and wss://, correct subprotocol header handling for browser client compatibility
- [ ] **Phase 6: MQTT 5.0** - MQTT 5.0 session expiry, user properties, reason codes, topic aliases, shared subscriptions, and protocol version negotiation
- [ ] **Phase 7: Hardening and Docker Release** - Soak tests, memory budget validation, startup warnings, ARM64 validation on real hardware, Docker image release

## Phase Details

### Phase 1: Foundation
**Goal**: The broker process starts, RocksDB persists data across restarts, Netty accepts TCP connections, Prometheus metrics are live, and the multi-arch Docker image runs on both amd64 and arm64 with a single `docker run` command
**Depends on**: Nothing (first phase)
**Requirements**: INFR-02, INFR-03, OPS-02, OPS-03, OPS-04, OPS-05, OPS-06
**Success Criteria** (what must be TRUE):
  1. `docker run -p 1883:1883 thingsboard/tbmq-lightweight` starts the broker with no external services required, and the broker accepts TCP connections on port 1883 within seconds
  2. RocksDB data (credentials, ACLs) written in one container run is readable after the container restarts with the same volume mount
  3. `GET /actuator/prometheus` returns a valid Prometheus text format response with at least connection count and JVM metrics
  4. The Docker image builds and runs correctly on both linux/amd64 and linux/arm64 (Debian/glibc base; no Alpine)
  5. The broker shuts down gracefully on `SIGTERM` — active channels are closed cleanly, RocksDB is flushed, and no JVM crash or `UnsatisfiedLinkError` occurs
**Plans:** 1/3 plans executed
Plans:
- [x] 01-01-PLAN.md — Maven project skeleton + RocksDB embedded storage layer
- [ ] 01-02-PLAN.md — Netty TCP bootstrap + Caffeine cache + Prometheus metrics
- [ ] 01-03-PLAN.md — Docker image + container verification checkpoint

### Phase 2: Core Protocol (MQTT 3.1.1)
**Goal**: A fully compliant MQTT 3.1.1 broker that any standard MQTT client can connect to, publish messages through, and subscribe to — with QoS 0/1/2, retained messages, LWT, keep-alive enforcement, and correct client takeover behavior — no authentication required in this phase
**Depends on**: Phase 1
**Requirements**: PROTO-01, PROTO-02, PROTO-03, PROTO-04, PROTO-05, PROTO-06, PROTO-07, PROTO-11, TRAN-01
**Success Criteria** (what must be TRUE):
  1. An MQTT 3.1.1 client (e.g., Paho Java) can CONNECT, PUBLISH, SUBSCRIBE, UNSUBSCRIBE, and DISCONNECT with correct CONNACK/SUBACK/UNSUBACK return codes
  2. QoS 0, QoS 1 (PUBACK), and QoS 2 (PUBREC/PUBREL/PUBCOMP) message flows complete correctly between publisher and subscriber; duplicate delivery on QoS 2 does not occur when the client sends DUP PUBLISH
  3. A retained message published to a topic is delivered to a new subscriber matching that topic immediately upon SUBSCRIBE, even if the original publisher is disconnected
  4. A client that exceeds its keep-alive timeout is disconnected and its LWT message is delivered to subscribers; a client that disconnects ungracefully also triggers LWT delivery
  5. When a second client connects with the same ClientID as an existing session, the existing session is closed (LWT suppressed for takeover), and the new session takes over without stale state
**Plans**: TBD

### Phase 3: Message Dispatch
**Goal**: Publish routing is handled by a standalone in-process dispatch service behind an abstracted interface, with wildcard subscription matching and backpressure so Netty I/O threads are never blocked
**Depends on**: Phase 2
**Requirements**: INFR-01, INFR-04
**Success Criteria** (what must be TRUE):
  1. Published messages are delivered to all matching subscribers (including `+` and `#` wildcard topics) via the in-process queue; Kafka is not required
  2. Under sustained publish load, Netty I/O thread event loops remain unblocked — verified by a spike load test showing no I/O stall events
  3. The dispatch implementation (LinkedBlockingQueue or Disruptor) can be swapped by changing a single configuration class without modifying any consumer code
**Plans**: TBD

### Phase 4: Security
**Goal**: Clients must authenticate before connecting, topic-level ACL rules are enforced on publish and subscribe, TLS terminates at the broker via mounted certificates, and X.509 client certificates are accepted for mutual TLS authentication — all backed by RocksDB so configuration survives restarts
**Depends on**: Phase 3
**Requirements**: AUTH-01, AUTH-02, AUTH-03, AUTH-04, AUTH-05, TRAN-02
**Success Criteria** (what must be TRUE):
  1. A client with valid username/password credentials stored in RocksDB can connect; a client with invalid or missing credentials is rejected with the correct CONNACK return code
  2. A client presenting a valid X.509 client certificate during mTLS handshake is authenticated without username/password; an invalid or expired certificate is rejected at the TLS layer
  3. A client is denied PUBLISH to a topic it has no ACL permission for, and denied SUBSCRIBE to a topic it has no read permission for; denials use correct MQTT return codes
  4. Anonymous connections are rejected by default; the broker accepts anonymous connections only when explicitly configured via environment variable
  5. After a broker restart, credentials and ACL rules loaded from the volume-mounted RocksDB directory are enforced without re-configuration
**Plans**: TBD

### Phase 5: WebSocket Transport
**Goal**: MQTT clients can connect over WebSocket (ws://) and secure WebSocket (wss://), and browser-based clients receive the correct `Sec-WebSocket-Protocol: mqtt` response header so the handshake is not rejected
**Depends on**: Phase 4
**Requirements**: TRAN-03, TRAN-04, TRAN-05
**Success Criteria** (what must be TRUE):
  1. An MQTT client (e.g., Paho JavaScript, MQTT.js) connects over `ws://` on the configured WebSocket port and publishes/subscribes successfully
  2. An MQTT client connects over `wss://` using TLS with the same mounted server certificate; the WebSocket and TLS layers both function correctly in combination
  3. The broker responds with `Sec-WebSocket-Protocol: mqtt` in the WebSocket handshake response; a browser-based MQTT.js client does not reject the connection
**Plans**: TBD

### Phase 6: MQTT 5.0
**Goal**: The broker accepts MQTT 5.0 clients and handles version-specific properties — session expiry interval, user properties, reason codes, topic aliases, and shared subscriptions — while simultaneously serving MQTT 3.1.1 clients on the same port
**Depends on**: Phase 5
**Requirements**: PROTO-08, PROTO-09, PROTO-10
**Success Criteria** (what must be TRUE):
  1. An MQTT 5.0 client and an MQTT 3.1.1 client can both be connected simultaneously; each receives protocol-version-appropriate responses (e.g., CONNACK with reason code for 5.0, return code for 3.1.1)
  2. A 5.0 client that sends session expiry interval, user properties, and topic aliases has those properties correctly handled — session expiry enforced, user properties forwarded, topic aliases resolved per-connection
  3. Shared subscription groups (`$share/group/topic`) distribute messages across active subscriber members; a message is delivered to exactly one member of the group per publish
**Plans**: TBD

### Phase 7: Hardening and Docker Release
**Goal**: The complete broker passes a 24-hour soak test with zero memory leaks or ByteBuf warnings, the Docker image is validated on real ARM64 hardware, Prometheus metrics are confirmed accurate under load, and the broker emits clear startup warnings for common misconfigurations before public release
**Depends on**: Phase 6
**Requirements**: OPS-01
**Success Criteria** (what must be TRUE):
  1. `GET /actuator/prometheus` returns accurate metrics under sustained load: connection count, message rates (in/out), auth success/failure counts, RocksDB read latency, and dispatch queue depth
  2. A 24-hour soak test with simulated production load shows zero Netty ByteBuf leak warnings (`PARANOID` detection) and stable container RSS (no unbounded growth)
  3. The Docker image runs correctly on a real ARM64 device (not QEMU emulation); the broker starts, accepts connections, and RocksDB persists data
  4. The broker logs a clear startup warning when TLS is not configured, when `/data/rocksdb` is not volume-mounted, and when retained messages are in-memory only
**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5 → 6 → 7

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Foundation | 1/3 | In Progress|  |
| 2. Core Protocol (MQTT 3.1.1) | 0/TBD | Not started | - |
| 3. Message Dispatch | 0/TBD | Not started | - |
| 4. Security | 0/TBD | Not started | - |
| 5. WebSocket Transport | 0/TBD | Not started | - |
| 6. MQTT 5.0 | 0/TBD | Not started | - |
| 7. Hardening and Docker Release | 0/TBD | Not started | - |
