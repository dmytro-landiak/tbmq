# Phase 7: Hardening and Docker Release - Context

**Gathered:** 2026-04-11
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase delivers pre-release validation and production readiness for the TBMQ Lightweight broker: completing the Prometheus metrics surface (message rates, auth counters, dispatch queue depth), a 24-hour soak test proving zero ByteBuf leaks and stable memory, startup warnings for common misconfigurations, ARM64 validation on real hardware via a manual test script, and a release-ready Docker image.

</domain>

<decisions>
## Implementation Decisions

### Metrics Completeness
- **D-01:** Add only metrics required by success criteria SC-1: message rates in/out (counters), auth success/failure counts (counters), and dispatch queue depth (gauge). No additional operational metrics in R1 — expand later.
- **D-02:** Dispatch queue depth is a simple Gauge (current queue size snapshot), matching the pattern used by `ConnectionCountHandler`. No histogram or high-water-mark tracking.
- **D-03:** Auth success/failure counters are incremented in the auth service layer (not the actor), so all transport types (TCP, TLS, WS, WSS) are covered automatically.
- **D-04:** Verify `mqtt.messages.received.total` is actually incremented in all PUBLISH paths. Add `mqtt.messages.delivered.total` for outbound message rate.

### Soak Test Strategy
- **D-05:** Soak test is a custom JUnit-based long-running integration test using Paho/HiveMQ client libraries already in the project. No external tooling (JMeter, emqtt-bench) required.
- **D-06:** Load profile: 500 concurrent clients, 1,000 msg/sec sustained, mix of QoS 0/1/2. Duration configurable — default 1 hour for CI, 24 hours for manual full soak.
- **D-07:** Netty ByteBuf leak detection set to `PARANOID` (`-Dio.netty.leakDetection.level=PARANOID`) during soak tests. Assert zero leak warnings in log output.
- **D-08:** RSS stability validated via JVM heap metrics from Micrometer (`jvm.memory.used`). Sample at intervals during the test, assert final heap within 20% of steady-state baseline. No OS-level `/proc` RSS tracking needed.

### Startup Warnings
- **D-09:** Startup warnings use `log.warn()` with a prominent multi-line banner block (bordered with `===`). Visible in `docker logs` output.
- **D-10:** Warnings triggered on `ApplicationReadyEvent` — runs after all SmartLifecycle components have started, so RocksDB/Netty/TLS initialization state is known.
- **D-11:** Three warning conditions: (1) TLS not configured (`tbmq.tls.enabled=false`), (2) `/data/rocksdb` not volume-mounted (detected via `/proc/mounts` overlay filesystem check), (3) retained messages are in-memory only (always true in R1 — informational reminder).
- **D-12:** Volume-mount detection: read `/proc/mounts` and check if the RocksDB data path's filesystem type is `overlay` (Docker's default for non-mounted paths). If overlay, warn that data will be lost on container removal.

### ARM64 Validation
- **D-13:** ARM64 validation is a manual test script (`scripts/arm64-validate.sh`) — no CI self-hosted runner or QEMU in R1.
- **D-14:** Script scope is core smoke test: container starts, MQTT client connects over TCP, publishes and receives a message, RocksDB data survives container restart. ~5 minute runtime.
- **D-15:** Script is documented and runnable on any ARM64 device: Raspberry Pi, Mac M-series via Docker Desktop, AWS Graviton instance.

### Claude's Discretion
- Exact metric names and tag labels (follow Micrometer/Prometheus conventions)
- Soak test JUnit class structure and assertion mechanics
- Startup warning banner format and exact wording
- ARM64 validation script implementation details (mosquitto_pub/sub vs. Paho CLI)
- Whether to add a `docker-compose.soak.yml` for convenient soak test execution
- Soak test steady-state baseline sampling strategy (warmup period, sample interval)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Existing Metrics Code (to extend)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsService.java` — Central metrics registration service; add new counters here
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/ConnectionCountHandler.java` — Pattern for Gauge registration (AtomicInteger + Micrometer)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/storage/rocksdb/DefaultRocksDbStorage.java` — RocksDB latency timer pattern
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java` — Dispatch queue; add queue depth gauge here

### Auth Service (metrics insertion point)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/` — Auth service layer where success/failure counters should be incremented

### Docker
- `lightweight/docker/Dockerfile` — Current Dockerfile; leak detection level comment at line 53 references Phase 7 PARANOID setting

### Configuration
- `lightweight/src/main/resources/tbmq-lightweight.yml` — All current config; startup warning checks read from these properties

### Project Context
- `.planning/PROJECT.md` — Core value, constraints, no-persistence-in-R1
- `.planning/REQUIREMENTS.md` — OPS-01 (metrics endpoint requirement)
- `.planning/phases/01-foundation/01-CONTEXT.md` — Docker, RocksDB, metrics foundation decisions

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `BrokerMetricsService` — Central place for metric registrations; extend with new counters
- `ConnectionCountHandler` — Pattern: AtomicInteger + Gauge.builder() for real-time gauges
- `DefaultMsgDispatcherService` — Already has `droppedMsgsCounter`; add queue depth gauge alongside
- Paho Java + HiveMQ MQTT Client — Both already in test dependencies for soak test client
- `AbstractMqttIntegrationTest` — Base test class with connection helpers; extend for soak tests

### Established Patterns
- Micrometer Counter/Gauge registration via `@PostConstruct` or `SmartLifecycle.start()`
- `@EventListener(ApplicationReadyEvent.class)` used for post-startup operations (e.g., `DefaultCredentialsInstaller`)
- YAML config with env var overrides: `"${ENV_VAR_NAME:default_value}"`
- Dockerfile: multi-stage build, `eclipse-temurin:17-jre-jammy` base, HEALTHCHECK via curl

### Integration Points
- Auth service layer — increment success/failure counters when auth result is computed
- `DefaultMsgDispatcherService.dispatch()` — increment messages-received counter; add queue depth gauge on `queue.size()`
- `ClientActor` delivery path — increment messages-delivered counter when PUBLISH is written to channel
- New `StartupWarningService` — `@EventListener(ApplicationReadyEvent)`, inject TLS config, RocksDB config, check conditions

</code_context>

<specifics>
## Specific Ideas

- The Dockerfile already has a comment at line 53 noting Phase 7 enables PARANOID for soak tests — the soak test should override this via JVM arg
- `mqtt.messages.received.total` is registered in `BrokerMetricsService` but needs verification that it's incremented in all PUBLISH handling paths (MqttSessionHandler and/or ClientActor)
- Startup warning for "retained messages in-memory only" is always true in R1 — it serves as a reminder that data doesn't survive restarts, useful for production users
- Volume mount detection via `/proc/mounts`: parse the file, find the line matching the RocksDB path, check the filesystem type field — `overlay` means Docker-managed (not mounted), anything else (ext4, xfs, etc.) means volume-mounted

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 07-hardening-and-docker-release*
*Context gathered: 2026-04-11*
