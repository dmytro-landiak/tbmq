# Project Research Summary

**Project:** TBMQ Lightweight — Embedded MQTT Broker
**Domain:** Single-node, zero-external-dependency MQTT broker (Java/JVM, single Docker image)
**Researched:** 2026-04-02
**Confidence:** HIGH

## Executive Summary

TBMQ Lightweight is a single-node MQTT broker built to fill the gap between Mosquitto (C, flat-file config, no metrics) and the full TBMQ stack (Kafka, PostgreSQL, clustering). The right approach is to extract and strip the existing TBMQ actor system — removing all external dependencies (Kafka, Redis, PostgreSQL) — and replace them with in-process equivalents: an LMAX Disruptor ring buffer for message dispatch, RocksDB for durable credentials and ACLs, and Caffeine for in-memory caching. The architecture is well-understood because the core protocol engine is inherited from TBMQ rather than built from scratch.

The recommended stack is Java 17 + Spring Boot 3.5.x + Netty 4.1.x (stay on 4.1 for R1; 4.2 has breaking changes) + RocksDB 9.7.4 + Caffeine 3.2.3 + LMAX Disruptor 4.0.0. The Docker base image must be `eclipse-temurin:17-jre-jammy` (Debian/glibc) — Alpine is hard-blocked by RocksDB JNI incompatibility with musl libc. This is a non-negotiable constraint that must be enforced from day one.

The primary risks are: (1) architectural — the Netty I/O thread must never block on the Disruptor ring buffer, requiring a careful non-blocking handoff from transport to dispatch; (2) protocol correctness — QoS 2 state machines and LWT-on-client-takeover are spec traps that major brokers have shipped with bugs; (3) operational — RocksDB JNI consumes native memory outside the JVM heap, requiring explicit container memory budgeting beyond `-Xmx`. All three risks are well-documented, have clear prevention patterns, and must be addressed in specific phases rather than left to post-R1 hardening.

---

## Key Findings

### Recommended Stack

The stack is anchored by existing TBMQ choices — Spring Boot DI, Netty transport, RocksDB storage — meaning this is an adaptation project, not a greenfield build. The critical additions are Caffeine (replaces Redis for in-process caching) and LMAX Disruptor (replaces Kafka for in-process message dispatch). Spring Boot 3.5.x is the correct choice despite its overhead because the shared actor system and protocol logic only work if both projects share the same DI container; switching to Micronaut or Quarkus would require rewriting the inherited core.

**Core technologies:**
- **Java 17 LTS**: Runtime minimum; balances ARM device compatibility with modern JVM features.
- **Spring Boot 3.5.x**: DI container + Actuator metrics; enables shared library extraction from main TBMQ. Use 3.5.9.
- **Netty 4.1.128.Final**: TCP/WebSocket/TLS transport; stay on 4.1.x — Netty 4.2 has breaking API changes (NioEventLoopGroup deprecated, codec module split) that add migration risk before R1 ships.
- **LMAX Disruptor 4.0.0**: In-process message dispatch; 3-10x throughput over BlockingQueue on the publish-to-subscribers path. Start with `LinkedBlockingQueue` behind an interface, swap to Disruptor if benchmarks show saturation.
- **RocksDB 9.7.4 (rocksdbjni)**: Embedded KV store for credentials, ACLs, auth provider config. Already in ThingsBoard ecosystem. Use 9.7.4 — v10.x is too fresh (Dec 2025).
- **Caffeine 3.2.3**: In-process session/credential/ACL cache; replaces Redis. Spring Boot default cache provider; near-optimal hit rates under high concurrency.
- **eclipse-temurin:17-jre-jammy**: Docker base image. Debian/glibc required — Alpine/musl is incompatible with RocksDB JNI and will cause `UnsatisfiedLinkError` at startup on ARM64.

**Hard constraints:**
- Never use Alpine as a Docker base image while RocksDB JNI is in the stack.
- Pin Netty to 4.1.x explicitly — do not let Spring Boot or dependency management upgrade to 4.2.
- Use RocksDB 9.7.4, not 10.x.
- Use `bcrypt` (cost ≥ 10) for credential hashing — never store plaintext passwords.

### Expected Features

Full research in `.planning/research/FEATURES.md`. The competitive landscape has shifted: EMQX moved to BSL 1.1 in May 2025, making its free tier a single-node broker like TBMQ Lightweight. This creates a genuine licensing differentiator for an Apache 2.0 or similarly open-licensed Java broker. TBMQ Lightweight's key gap to fill vs Mosquitto: native Prometheus metrics, RocksDB-backed credentials (vs flat file), and secure-by-default configuration.

**Must have (table stakes) — R1:**
- MQTT 3.1.1 and 5.0 full compliance — protocol correctness is the entire value proposition
- QoS 0/1/2 including full QoS 2 state machine — QoS 2 is hard to get right; ship it or document its absence
- TLS (server-side, mounted certs) — security baseline
- MQTT over WebSocket (ws:// and wss://) — browser clients require this
- Username/password auth backed by RocksDB — survives restarts; flat files are fragile
- X.509 certificate-based client auth — industrial/automotive use case; clear differentiator vs Mosquitto
- Topic-level ACL in RocksDB — authorization that survives restarts
- Retained messages (in-memory for R1) — core "last known value" pattern
- Last Will and Testament — device disconnection signaling
- Prometheus /metrics endpoint (native via Micrometer) — no external exporter required
- Single Docker image, zero config for defaults, `docker run -p 1883:1883 image:tag` works

**Should have (competitive differentiators) — R1/R2:**
- MQTT 5.0 shared subscriptions — load-balancing subscriber groups; Mosquitto lacks this
- Multi-arch Docker image (amd64 + arm64) — edge deployments run ARM
- Zero-config secure default — block anonymous access unless `ALLOW_ANONYMOUS=true` is explicitly set

**Defer (v2+):**
- Persistent sessions / offline message queuing — R2 when durable storage is proven
- Durable retained messages (RocksDB-backed) — R2
- REST management API — R2 before Web UI
- Web UI — R3 or later
- Clustering / HA — standard TBMQ value proposition; adding it to Lightweight creates product confusion
- Rule engine — enterprise tier concern

### Architecture Approach

The architecture is a four-layer stack: Transport (Netty) → Actor (per-ClientId state machine) → Service (auth, subscription trie, dispatch, session) → Storage (RocksDB + in-process queue). This is inherited from TBMQ and proven at scale. The key adaptation is replacing all external I/O (Kafka for dispatch, Redis for cache, PostgreSQL for state) with in-process equivalents that have zero external dependencies. The `common/actor`, `common/data`, `common/stats`, and `common/util` modules from TBMQ are directly reusable; `ClientActor` needs Kafka session event coordination stripped out and replaced with direct in-process calls. Full component map in `.planning/research/ARCHITECTURE.md`.

**Major components:**
1. **Netty Bootstrap** — binds TCP:1883, TLS:8883, WS:8084, WSS:8085; manages boss/worker EventLoopGroups; uses EpollEventLoopGroup on Linux.
2. **MqttSessionHandler** — per-channel Netty handler; decodes MQTT frames, rate-limits, dispatches to ClientActor (non-blocking enqueue).
3. **ClientActor** — owns the lifecycle of one clientId; processes CONNECT, PUBLISH, SUBSCRIBE, DISCONNECT, keep-alive timeout serially from an actor mailbox; no locks needed inside the actor.
4. **SubscriptionTrie** — `ConcurrentHashMap`-backed trie for wildcard `+`/`#` topic matching; concurrent reads without global lock; `ConcurrentMapSubscriptionTrie` from TBMQ is reusable.
5. **MsgDispatcherService** — in-process queue (Disruptor or LinkedBlockingQueue); receives published messages, resolves subscribers via trie, delivers to matching sessions.
6. **AuthService** — validates credentials (Basic + X.509) against RocksDB; caches hot entries in Caffeine; evaluates ACL rules per PUBLISH/SUBSCRIBE.
7. **RocksDB storage** — 4 column families: `credentials`, `acl_rules`, `auth_providers`, `admin_settings`; all opened in a single `RocksDB.open()` call; volume-mounted at `/data/rocksdb`.
8. **MetricsService** — Micrometer + Prometheus registry via Spring Boot Actuator; `GET /actuator/prometheus`.

**Critical architectural rule:** Netty I/O threads must never block. The handoff from `MqttSessionHandler` to `ClientActor` is always an async non-blocking enqueue. The handoff from `ClientActor` to `MsgDispatcherService` uses `ringBuffer.tryPublishEvent()` (non-blocking); if full, apply TCP backpressure via `channel.config().setAutoRead(false)`.

### Critical Pitfalls

Full detail in `.planning/research/PITFALLS.md`. Top pitfalls with highest recovery cost:

1. **Disruptor blocks Netty I/O thread** — if `ringBuffer.publishEvent()` (blocking) is called from a Netty event loop thread, a full ring buffer stalls all I/O on that event loop. Prevention: always use `tryPublishEvent()` from I/O threads; implement backpressure via `setAutoRead(false)`. This requires architectural design up front — retrofitting is expensive.

2. **Netty ByteBuf leaks under long-running load** — reference-counted ByteBufs not released in all code paths cause native memory exhaustion. Prevention: enable `PARANOID` leak detection in CI; use `SimpleChannelInboundHandler` to auto-release; explicitly retain/release any buffer held beyond the handler. Requires 24-hour soak test validation.

3. **RocksDB JNI native memory exceeds container budget** — RocksDB block cache, memtables, and index blocks live outside JVM heap. Container OOMs when only `-Xmx` is considered. Prevention: size container as `heap + RocksDB block cache + Netty direct buffers + 20% overhead`; set `cache_index_and_filter_blocks=true`; call `close()` on all RocksDB objects in `@PreDestroy`.

4. **LWT fires on client takeover** — when client B connects with the same ClientID as client A, LWT from A fires incorrectly. Prevention: track disconnect reason per session; suppress LWT when disconnect is caused by a new CONNECT for the same ClientID. MQTT 5.0 Will Delay Interval must be honored.

5. **QoS 2 phantom PUBREL after reconnect** — without deduplication of re-received DUP PUBLISH, messages are delivered twice. Prevention: maintain `Set<Integer> qos2ReceivedIds` per session; deduplicate on DUP PUBLISH against in-flight table. Document that cross-restart QoS 2 guarantee requires persistent sessions (R2).

6. **RocksDB JNI fails on ARM64 Alpine** — Alpine musl libc is incompatible with RocksDB's glibc native libraries. Prevention: enforce `eclipse-temurin:17-jre-jammy` as base image with an explicit comment in Dockerfile from the first commit. Lock the image in CI.

---

## Implications for Roadmap

Based on research, the project has clear hard dependencies that constrain ordering. Feature dependencies (X.509 requires TLS, ACL requires auth, MQTT 5.0 requires 3.1.1, WSS requires both WS and TLS) and architectural dependencies (transport before actors before services before storage) both point to a bottom-up build order. The suggested phase structure below builds on proven TBMQ modules and validates each layer before the next depends on it.

### Phase 1: Project Foundation and Infrastructure

**Rationale:** No features are buildable without the runtime foundation. RocksDB JNI, Docker multi-arch, and the Netty bootstrap must be validated before any protocol work begins. The Alpine/musl constraint (Pitfall 4) must be enforced from the first Dockerfile commit. This phase has the lowest protocol complexity but the highest infrastructure risk.

**Delivers:** Running Spring Boot application in a multi-arch Docker image (amd64 + arm64); RocksDB opens and persists data across container restarts; Netty accepts TCP connections; Prometheus `/actuator/prometheus` endpoint live.

**Addresses:** Docker single-command startup (table stakes); Prometheus /metrics (table stakes); multi-arch image (differentiator).

**Avoids:** RocksDB ARM64/Alpine failure (lock base image in first Dockerfile commit); RocksDB native memory OOM (establish container memory budget and `cache_index_and_filter_blocks=true` from day one).

**Research flag:** Standard patterns. Docker Buildx multi-arch, Spring Boot Actuator, and RocksDB column family setup are all well-documented. No additional research needed.

---

### Phase 2: Core Protocol Engine — MQTT 3.1.1 + Actor Layer

**Rationale:** All other features depend on a correct MQTT 3.1.1 implementation. This phase stands up the full actor system (ClientActor, SessionService, LwtService) and the CONNECT/PUBLISH/SUBSCRIBE/DISCONNECT flows. QoS 0 is the validation gate; QoS 1 and QoS 2 are included here because the QoS state machine is part of the session actor, not a separate concern.

**Delivers:** Fully compliant MQTT 3.1.1 broker with QoS 0/1/2, retained messages (in-memory), LWT, and anonymous access (no auth yet). Passes MQTT conformance tests (e.g., `mqtt-conformance-test`, `paho.mqtt.testing`).

**Addresses:** MQTT 3.1.1 full compliance (P1); QoS 0/1/2 flows (P1); retained messages in-memory (P1); LWT (P1).

**Avoids:** LWT on client takeover (implement disconnect reason tracking from the start); QoS 2 phantom PUBREL (implement `qos2ReceivedIds` dedup set in ClientActor from the start); keep-alive false disconnects (use 1.75× keepAlive + track "last MQTT packet" time, not TCP byte time).

**Research flag:** MQTT 3.1.1 spec is authoritative (OASIS). QoS 2 state machine transitions are well-specified. No additional research needed beyond spec; focus on conformance testing.

---

### Phase 3: In-Process Message Dispatch

**Rationale:** Publish routing is the performance-critical path. It must be designed as a standalone component (behind a `PublishMsgQueueFactory` interface) before being consumed by the actor layer. The Disruptor / BlockingQueue choice is made here, and the non-blocking handoff from Netty I/O threads is the critical architectural constraint to validate under load.

**Delivers:** `MsgDispatcherService` backed by `LinkedBlockingQueue` initially; `SubscriptionTrie` (ConcurrentMap-backed) for wildcard matching; dispatch throughput benchmarked with JMH; spike load test confirming Netty I/O threads are never blocked.

**Addresses:** All PUBLISH/SUBSCRIBE flows; subscription matching with `+` and `#` wildcards.

**Avoids:** Disruptor blocks Netty I/O thread (enforce non-blocking `tryPublishEvent` + `setAutoRead(false)` backpressure); subscription trie contention (use ConcurrentHashMap-backed trie from day one; benchmark with 1,000 concurrent wildcard subscribers before declaring complete); packet ID exhaustion (keep QoS 1 PUBACK path entirely in-memory for clean-session R1 — no RocksDB writes on the ACK path).

**Research flag:** LMAX Disruptor patterns are well-documented. Start with `LinkedBlockingQueue` and swap to Disruptor only if benchmarks show saturation — avoid premature optimization. Research the specific `tryPublishEvent` + backpressure pattern if Disruptor is adopted.

---

### Phase 4: Security — Authentication and ACL

**Rationale:** Auth and ACL share RocksDB storage and Caffeine caching; they must be built together. ACL is meaningless without an identity, so auth ships first in this phase. TLS is a prerequisite for X.509 auth, so TLS is also in this phase.

**Delivers:** Username/password auth backed by RocksDB (bcrypt hashed); Topic-level ACL in RocksDB; TLS (server-side cert mount); X.509 certificate-based client auth (mTLS); Caffeine cache for hot auth paths; anonymous access blocked by default (`ALLOW_ANONYMOUS=false`).

**Addresses:** Username/password auth (P1); Topic ACL (P1); TLS (P1); X.509 cert auth (P1 differentiator); zero-config secure default (differentiator).

**Avoids:** Anonymous access by default (default to closed; `ALLOW_ANONYMOUS=true` env var is opt-in); plaintext credential storage (bcrypt only); ACL evaluated post-delivery (enforce ACL at SUBSCRIBE time and at routing time); TLS cert validation at startup (fail fast on malformed or expired certs); RocksDB synchronous reads on CONNECT path (Caffeine cache in front of RocksDB from day one to survive connection storms).

**Research flag:** Standard patterns for bcrypt with Spring Security (or standalone BCrypt). X.509 mTLS in Netty is well-documented in existing TBMQ codebase. No additional research needed.

---

### Phase 5: Transport — WebSocket and TLS Hardening

**Rationale:** WebSocket support is a table-stakes requirement (browser clients, IoT dashboards). It is built after the core TCP protocol and TLS are validated because WSS = WS + TLS, and combining them before each is individually stable adds debugging complexity.

**Delivers:** MQTT over WebSocket (ws://8084, wss://8085); correct `Sec-WebSocket-Protocol: mqtt` handshake response (browser clients); TLS cert validation at startup with clear error messages; documented cert rotation procedure.

**Addresses:** MQTT over WebSocket (P1 table stakes); wss:// (secure WebSocket).

**Avoids:** WebSocket subprotocol header rejection (configure `WebSocketServerProtocolHandler` with `subprotocols: "mqtt"`; acceptance criterion must include a browser-based `mqttjs` test, not just Paho Java); TLS cert rotation downtime (document the restart-required limitation at R1; add cert-reload endpoint to R2 backlog); Netty ByteBuf leaks (run 24-hour load test with `PARANOID` leak detection before declaring this phase complete).

**Research flag:** WebSocket subprotocol header behavior in Netty's `WebSocketServerProtocolHandler` — verify current behavior with `subprotocols: "mqtt"` configuration. One-time check.

---

### Phase 6: MQTT 5.0

**Rationale:** MQTT 5.0 requires a complete and correct 3.1.1 implementation as its foundation (protocol version is negotiated at CONNECT). Building 5.0 on a fully tested 3.1.1 base reduces the debugging surface significantly. All 5.0 additions are layered on existing handlers — no architectural changes required.

**Delivers:** Full MQTT 5.0 compliance including session expiry interval, user properties, reason codes in CONNACK/PUBACK/SUBACK/DISCONNECT, AUTH packet handling (return `0x8C Bad Authentication Method` if enhanced auth not supported), shared subscriptions, and protocol version negotiation (5.0 client + 3.1.1 client coexisting on the same broker).

**Addresses:** MQTT 5.0 full compliance (P1); shared subscriptions (P1/P2 differentiator); MQTT 5.0 session expiry interval (differentiator); MQTT 5.0 user properties (differentiator).

**Avoids:** MQTT 5.0 vs 3.1.1 code path mixing (protocol version must gate handler behavior; a handler that ignores version silently drops user properties, reason codes, and session expiry); AUTH packet ignored silently (return correct reason code rather than downgrading).

**Research flag:** MQTT 5.0 spec (OASIS) is authoritative. Shared subscriptions semantics for clean-session-only R1 need a clear scope decision: implement clean-session shared subscriptions; document that persistent consumer group semantics (full round-robin guarantee across restarts) is R2.

---

### Phase 7: Observability, Hardening, and Docker Release

**Rationale:** Final phase validates the complete system under sustained load before Docker image release. Confirms metrics are correct, memory budgets hold, and all "looks done but isn't" checklist items pass. This is not polish — it is the phase that catches systemic bugs only visible under production-like conditions.

**Delivers:** Validated Prometheus metrics (connection count, message rates, auth success/failure, RocksDB read latency, queue depth); 24-hour soak test with zero ByteBuf leak warnings; confirmed container memory stability under 10K concurrent connections; ARM64 Docker image validated on actual ARM64 hardware (not just QEMU); startup warning when plaintext port is active without TLS; startup warning when retained messages are in-memory only; startup warning when RocksDB `/data` is not volume-mounted; "Looks Done But Isn't" checklist completed.

**Addresses:** Prometheus /metrics (finalizes P1); multi-arch Docker image (P2 differentiator); Docker single-command startup with sensible defaults.

**Avoids:** RocksDB native memory OOM under production load (soak test with RSS monitoring); Netty ByteBuf leaks at scale (24-hour load test with `PARANOID`); silent failures (startup warnings for common misconfigurations).

**Research flag:** No additional research needed. Validation methodology is standard (JMH benchmarks, container memory monitoring, `PARANOID` leak detection).

---

### Phase Ordering Rationale

- **Infrastructure before protocol:** Phases 1-2 establish the runtime foundation. No protocol work is worth doing if the Docker image cannot run on ARM64 or if RocksDB cannot be opened.
- **Protocol correctness before security:** Phase 2 validates the MQTT state machine without the complexity of auth. Adding security on top of a correct protocol base is far easier than debugging auth and QoS 2 simultaneously.
- **Dispatch before auth:** Phase 3 is separate because the dispatch path is the performance-critical seam with its own architectural risk (non-blocking Netty handoff). Validating it in isolation, with load tests, before other features depend on it reduces risk.
- **Auth and ACL together in Phase 4:** Auth identity is required for ACL; they share RocksDB and Caffeine; separating them would create a half-working security layer.
- **WebSocket after TCP is stable (Phase 5):** WebSocket adds a new protocol layer on top of an existing TLS/TCP foundation. Building it before TLS and TCP are proven would make it harder to isolate failures.
- **MQTT 5.0 last in protocol work (Phase 6):** 5.0 depends on complete 3.1.1 (version negotiation) and on TLS being stable (enhanced auth, Will Delay Interval). It is additive, not structural.
- **Hardening as final phase (Phase 7):** Soak tests, memory budget validation, and the "looks done but isn't" checklist are only meaningful after all features are integrated.

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 3 (Dispatch):** If Disruptor adoption is chosen over LinkedBlockingQueue, the non-blocking `tryPublishEvent` + `setAutoRead(false)` backpressure wiring in Netty deserves a focused research spike. The pattern is documented but the exact Netty watermark configuration needs validation.
- **Phase 6 (MQTT 5.0):** Shared subscriptions semantics for clean-session-only brokers — specifically, round-robin guarantees and behavior when the shared subscription group has no active members. The MQTT 5.0 spec is clear but implementation edge cases benefit from reviewing existing broker implementations (EMQX, HiveMQ CE).

Phases with standard patterns (no additional research needed):
- **Phase 1 (Foundation):** Docker Buildx multi-arch + Spring Boot Actuator + RocksDB column families are fully documented.
- **Phase 2 (Core Protocol):** MQTT 3.1.1 spec is authoritative; existing TBMQ actor system is the implementation reference.
- **Phase 4 (Security):** bcrypt + RocksDB KV + mTLS in Netty are all well-documented; existing TBMQ auth provider code is directly reusable.
- **Phase 7 (Hardening):** Soak testing and memory profiling methodology is standard engineering practice.

---

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Core choices (Spring Boot, Netty, RocksDB) are inherited from a production codebase. Version pins (Netty 4.1.x, RocksDB 9.7.4) are verified against official release notes and known breaking changes. LMAX Disruptor version is confirmed from GitHub releases. |
| Features | MEDIUM-HIGH | Table stakes and competitive differentiators are well-sourced. Competitive analysis is partially sourced from vendor blogs (EMQ, HiveMQ) which have bias; mitigated by cross-referencing official sources and independent reporting (HowToGeek). |
| Architecture | HIGH | Based on analysis of the existing TBMQ codebase combined with established MQTT broker architecture patterns. All major patterns (actor-per-client, concurrent trie, RocksDB column families) are verified against production implementations. |
| Pitfalls | HIGH | All critical pitfalls are backed by GitHub issues (RocksDB, Netty, EMQX, Mosquitto) or official specs. Recovery costs are estimated from real production incidents, not speculation. |

**Overall confidence:** HIGH

### Gaps to Address

- **RocksDB schema evolution strategy:** No migration framework (no Flyway-equivalent for RocksDB). A `metadata` column family with a schema version key is the recommended pattern, but the exact migration execution strategy (in-process on startup vs offline tool) needs a concrete decision before R2 features that change the schema.
- **Performance targets:** No explicit connection count or message throughput target was defined in the project brief. Phase 3 and Phase 7 benchmarks need a target to benchmark against. Recommended: define a floor of 10,000 concurrent connections and 50,000 msg/sec sustained before the project brief is finalized.
- **Persistent session scope for R2:** The decision to defer persistent sessions to R2 is correct, but the R2 design (per-client durable message queue in RocksDB) needs early prototyping to confirm RocksDB column family layout before R1 storage schema is locked. A forward-compatible storage layout for R1 avoids a painful migration in R2.
- **License:** The project brief did not specify a license. Given that EMQX's BSL move is a competitive opportunity for an open-source Java broker, the license decision should be made before the repository is published.

---

## Sources

### Primary (HIGH confidence)

- MQTT 5.0 specification (OASIS) — protocol compliance requirements: https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html
- TBMQ architecture documentation — actor system, Netty transport, auth provider chain: https://thingsboard.io/docs/mqtt-broker/architecture/
- Netty 4.1.128.Final release notes: https://netty.io/news/2025/10/14/4-1-128-Final.html
- Netty 4.2 migration guide (breaking changes): https://netty.io/wiki/netty-4.2-migration-guide.html
- Spring Boot 3.5.9 release notes: https://spring.io/blog/2025/12/18/spring-boot-3-5-9-available-now/
- RocksDB GitHub releases (9.7.4 stable, 10.10.1 fresh): https://github.com/facebook/rocksdb/releases
- RocksDB JNI native memory leak post-v7 (GitHub #9962, #12020): https://github.com/facebook/rocksdb/issues/9962
- RocksDB ARM64 Alpine musl failure (GitHub #10651): https://github.com/facebook/rocksdb/issues/10651
- LMAX Disruptor 4.0.0 release + performance paper: https://github.com/LMAX-Exchange/disruptor/releases
- EMQX BSL 1.1 license announcement: https://www.emqx.com/en/blog/adopting-business-source-license-to-accelerate-mqtt-and-ai-innovation
- HiveMQ Community Edition (Apache 2.0, unlimited connections): https://github.com/hivemq/hivemq-community-edition
- Your MQTT broker might be public (HowToGeek, 2025 — anonymous access risk): https://www.howtogeek.com/your-mqtt-broker-might-be-public/
- EMQX QoS 2 DUP reconnect bug (GitHub #14688, Feb 2025): https://github.com/emqx/emqx/issues/14688
- Mosquitto LWT client takeover bug (GitHub #904): https://github.com/eclipse/mosquitto/issues/904
- WebSocket subprotocol header rejection (mqttjs GitHub #408): https://github.com/mqttjs/MQTT.js/issues/408

### Secondary (MEDIUM confidence)

- EMQX Open Source vs Enterprise feature comparison: https://www.emqx.com/en/blog/emqx-open-source-vs-enterprise
- HiveMQ vs Mosquitto comparison: https://www.hivemq.com/blog/hivemq-vs-mosquitto-an-mqtt-broker-comparison/
- HiveMQ MQTT Topic Tree Matching Challenges: https://www.hivemq.com/blog/mqtt-topic-tree-matching-challenges-best-practices-explained/
- Caffeine vs Guava Cache benchmarks: https://github.com/ben-manes/caffeine/wiki/Benchmarks
- MapDB low commit velocity assessment (2024): https://marekhudyma.com/java/2024/07/01/mapdb.html
- Netty ByteBuf memory leak production postmortem: https://logz.io/blog/netty-bytebuf-memory-leak/
- LMAX Disruptor ring buffer blocking under full buffer (Google Groups): https://groups.google.com/g/lmax-disruptor/c/8laL6xd7ag4

---
*Research completed: 2026-04-02*
*Ready for roadmap: yes*
