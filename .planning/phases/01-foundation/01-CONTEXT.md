# Phase 1: Foundation - Context

**Gathered:** 2026-04-02
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase delivers a runnable Spring Boot application that: bootstraps Netty to accept TCP connections on port 1883, initializes RocksDB for persistent key-value storage, exposes a Prometheus-compatible metrics endpoint, and packages everything into a multi-arch Docker image (amd64 + arm64). No MQTT protocol handling in this phase — just the infrastructure skeleton.

</domain>

<decisions>
## Implementation Decisions

### Project Structure
- **D-01:** Use a flat modular Maven structure — single root POM with package-level separation (not TBMQ's deep multi-module hierarchy). Packages: `org.thingsboard.mqtt.broker.lightweight.server` (Netty bootstrap), `org.thingsboard.mqtt.broker.lightweight.storage` (RocksDB), `org.thingsboard.mqtt.broker.lightweight.config` (Spring config), `org.thingsboard.mqtt.broker.lightweight.metrics` (Prometheus).
- **D-02:** Start as a fully self-contained repo with local code — no shared library extraction from TBMQ in R1. Copy-first, extract-later reduces coordination overhead.
- **D-03:** Follow TBMQ naming conventions (interface + `Default` prefix implementation in application code, `Impl` suffix for DAO layer, `Entity` suffix for persistence models).

### RocksDB Schema Design
- **D-04:** Use one RocksDB column family per entity type: `credentials`, `acl_rules`, `retained_messages`, `metadata`. The `metadata` CF stores a `schema_version` key for R2 migration compatibility.
- **D-05:** Keys are UTF-8 strings (client ID, username, topic). Values are JSON-serialized (Jackson) for readability and debuggability in R1; binary serialization is a post-R1 optimization if needed.
- **D-06:** All RocksDB objects (`RocksDB`, `ColumnFamilyHandle`, `Options`, `WriteOptions`, `ReadOptions`) MUST be explicitly closed — finalizers were removed in RocksDB v7+. Use try-with-resources or Spring `@PreDestroy`.
- **D-07:** RocksDB lifecycle managed via Spring `SmartLifecycle` with explicit phase ordering — RocksDB starts first (lowest phase), stops last (highest phase). All services that depend on RocksDB must declare a higher SmartLifecycle phase.

### Netty Bootstrap
- **D-08:** Use boss/worker thread model: 1 boss thread for accepting connections, worker threads = available CPU cores. Use native Epoll transport on Linux (`EpollEventLoopGroup`, `EpollServerSocketChannel`) with NIO fallback on other platforms.
- **D-09:** Channel pipeline in Phase 1 is minimal: `IdleStateHandler` (keep-alive placeholder) → connection counter handler. MQTT codec and protocol handlers are added in Phase 2.
- **D-10:** Connection limit configurable via environment variable (default: 10,000). Reject new connections with channel close when limit reached.

### Docker and Deployment
- **D-11:** Docker image: `thingsboard/tbmq-lightweight`, base: `eclipse-temurin:17-jre-jammy` (NOT Alpine — RocksDB JNI is musl-incompatible). Multi-stage build: Maven build stage → runtime stage.
- **D-12:** Persistent data path: `/data/rocksdb` inside the container, volume-mountable. Default data directory if no volume mounted: `/data/rocksdb` (ephemeral, lost on container removal).
- **D-13:** Environment variable convention: `TBMQ_` prefix for all broker-specific configuration (e.g., `TBMQ_MQTT_PORT`, `TBMQ_ROCKSDB_PATH`, `TBMQ_METRICS_ENABLED`). Spring Boot's relaxed binding maps these to YAML properties.
- **D-14:** Docker healthcheck: `curl -sf http://localhost:8083/actuator/health || exit 1` (Spring Boot Actuator health endpoint).

### Metrics and Observability
- **D-15:** Use Spring Boot Actuator + Micrometer with Prometheus registry. Endpoint: `/actuator/prometheus`. Include JVM metrics (memory, GC, threads) and custom broker metrics (connection count, RocksDB read/write latency).
- **D-16:** Caffeine cache metrics exposed via Micrometer's `CaffeineCacheMetrics` — cache hit/miss rates visible in Prometheus.

### In-Process Cache
- **D-17:** Use Caffeine for all in-process caching (session state lookups, subscription matching, credential checks). Configure via Spring Boot's `spring.cache` properties. Maximum cache sizes configurable via environment variables.

### Claude's Discretion
- Exact Maven artifact IDs and module naming
- Spring Boot configuration property naming (as long as `TBMQ_` prefix convention is followed)
- Specific Netty channel handler class names and package layout
- Prometheus metric names (follow Micrometer conventions)
- Dockerfile COPY ordering and layer optimization

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Existing TBMQ Codebase (patterns to follow)
- `application/src/main/java/org/thingsboard/mqtt/broker/ThingsboardMqttBrokerApplication.java` — Main entry point pattern
- `application/src/main/resources/thingsboard-mqtt-broker.yml` — YAML configuration structure to reference
- `common/actor/src/main/java/org/thingsboard/mqtt/broker/actors/TbActorSystem.java` — Actor system (reusable in later phases)
- `msa/tbmq/docker/Dockerfile` — Existing Docker build pattern
- `pom.xml` — Root POM structure and dependency management pattern

### Research Documents
- `.planning/research/STACK.md` — Technology choices with versions and rationale
- `.planning/research/ARCHITECTURE.md` — Component boundaries and build order
- `.planning/research/PITFALLS.md` — RocksDB JNI pitfalls, Netty ByteBuf leaks, shutdown ordering

### Project Context
- `.planning/PROJECT.md` — Core value, constraints, key decisions
- `.planning/REQUIREMENTS.md` — INFR-02, INFR-03, OPS-02 through OPS-06

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `common/actor/` — Actor framework is generic and reusable, but not needed in Phase 1 (Phase 2+)
- `common/stats/` — Metrics/statistics framework patterns can inform Micrometer setup
- `common/util/` — Utility classes (Jackson config, threading) can be referenced for conventions

### Established Patterns
- Spring Boot + Netty coexistence: TBMQ runs both Spring MVC (REST) and Netty (MQTT) in the same JVM — same pattern needed here
- Configuration layering: YAML defaults → environment variable overrides → Docker env vars
- Lombok usage: `@Slf4j`, `@Data`, `@Builder`, `@RequiredArgsConstructor` used extensively
- Interface + Default implementation pattern in application module

### Integration Points
- Netty TCP listener runs independently of Spring MVC — both share the same Spring context but have separate thread pools
- RocksDB is a JNI library — `RocksDB.loadLibrary()` must be called before any DB operations, ideally in a `@PostConstruct` or `SmartLifecycle.start()`
- Spring Boot Actuator provides `/actuator/prometheus` and `/actuator/health` endpoints out of the box with Micrometer

</code_context>

<specifics>
## Specific Ideas

- Pin exact dependency versions from research: RocksDB 9.7.4, Caffeine 3.2.3, Netty 4.1.128.Final, Spring Boot 3.5.x
- Docker multi-arch build via `docker buildx` with `--platform linux/amd64,linux/arm64`
- STATE.md notes: include schema version metadata CF from day one for R2 migration path
- STATE.md notes: recommended performance floor of 10,000 concurrent connections — design Netty thread model accordingly

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 01-foundation*
*Context gathered: 2026-04-02*
