---
phase: 01-foundation
verified: 2026-04-03T15:15:00Z
status: human_needed
score: 12/13 must-haves verified
human_verification:
  - test: "ARM64 container validation"
    expected: "docker buildx build --platform linux/arm64 succeeds and container starts without UnsatisfiedLinkError from RocksDB JNI"
    why_human: "Local buildx has no arm64 QEMU emulator available (linux/amd64 only). OPS-03 requires arm64 support. The Dockerfile is correctly structured using eclipse-temurin:17-jre-jammy (glibc, not musl), which is the prerequisite. Actual arm64 execution requires hardware or QEMU registration."
---

# Phase 1: Foundation Verification Report

**Phase Goal:** The broker process starts, RocksDB persists data across restarts, Netty accepts TCP connections, Prometheus metrics are live, and the multi-arch Docker image runs on both amd64 and arm64 with a single `docker run` command
**Verified:** 2026-04-03T15:15:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| #  | Truth                                                                                          | Status     | Evidence                                                                                  |
|----|-----------------------------------------------------------------------------------------------|------------|-------------------------------------------------------------------------------------------|
| 1  | Spring Boot application context loads successfully                                             | VERIFIED   | `TbmqLightweightApplicationTest.contextLoads()` passes; `BUILD SUCCESS` across 21 tests  |
| 2  | RocksDB opens with four column families (credentials, acl_rules, retained_messages, metadata) | VERIFIED   | `RocksDbStorageTest.allColumnFamiliesAccessible()` passes; 4 enum values in `RocksDbColumnFamily`; single `RocksDB.open()` call confirmed in `DefaultRocksDbStorage.start()` |
| 3  | RocksDB put/get/delete operations work correctly per column family                            | VERIFIED   | Tests `putAndGetReturnsValue`, `putThenDeleteReturnsNull`, `allColumnFamiliesAccessible` all pass |
| 4  | RocksDB data persists across service stop/start cycles                                        | VERIFIED   | `RocksDbStorageTest.dataPersistedAcrossRestarts()` passes — stops, re-opens same path, retrieves original value |
| 5  | schema_version key is present in METADATA column family on first startup                     | VERIFIED   | `RocksDbStorageTest.schemaVersionInitializedOnFirstStartup()` passes; `DefaultRocksDbStorage.start()` writes "1" when null |
| 6  | Netty TCP server binds to port 1883 and accepts TCP connections                               | VERIFIED   | `NettyServerBootstrapTest.testTcpConnect_succeeds()` passes; Epoll/NIO detection in `MqttTcpServerBootstrap.start()` |
| 7  | Connection count is tracked and exposed as a Prometheus gauge                                 | VERIFIED   | `ConnectionCountHandler` registers `mqtt.connections.active` gauge; `GracefulShutdownTest.testPrometheusEndpoint_returnsMetrics()` asserts `mqtt_connections_active` in response |
| 8  | New connections are rejected when max connection limit is reached                             | VERIFIED   | `NettyServerBootstrapTest.testConnectionLimit_rejectsExcessConnections()` passes; `ctx.close()` in `ConnectionCountHandler.channelActive()` |
| 9  | Caffeine cache manager is registered and cache metrics are available                          | VERIFIED   | `CacheConfigurationTest` passes all 4 tests including D-16 metrics registration check; `CaffeineCacheMetrics.monitor()` explicitly called in `CacheConfiguration` |
| 10 | Prometheus endpoint returns JVM metrics and custom broker metrics                            | VERIFIED   | `GracefulShutdownTest.testPrometheusEndpoint_returnsMetrics()` asserts `jvm_memory_used_bytes`, `mqtt_connections_active`, `cache_gets_total` |
| 11 | Graceful shutdown closes Netty channels before RocksDB stops                                 | VERIFIED   | `GracefulShutdownTest.testLifecycleOrdering_nettyStartsAfterRocksDb()` passes; Netty phase=0 > Integer.MIN_VALUE (RocksDB); SmartLifecycle stops in descending order |
| 12 | Docker image builds and container starts with `docker run -p 1883:1883`                       | VERIFIED   | Human-approved in Task 2 checkpoint (plan 01-03); image `thingsboard/tbmq-lightweight:test` exists locally (369MB, eclipse-temurin:17-jre-jammy) |
| 13 | Docker image supports both linux/amd64 and linux/arm64                                       | PARTIAL    | Dockerfile uses `eclipse-temurin:17-jre-jammy` (glibc — ARM64 compatible, not alpine/musl); `maven:3.9-eclipse-temurin-17` build stage is multi-arch; local buildx has no arm64 QEMU registered; amd64 image built and human-verified; arm64 not validated |

**Score:** 12/13 truths verified (1 partial — OPS-03 arm64 not validated)

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `lightweight/pom.xml` | Maven project with Spring Boot 3.5.3, rocksdbjni 9.7.4, Netty 4.1.122.Final, Caffeine, Actuator, Prometheus | VERIFIED | All expected dependencies present; `<version>3.5.3</version>`, `<rocksdb.version>9.7.4</rocksdb.version>`, `<netty.version>4.1.122.Final</netty.version>`, `micrometer-registry-prometheus`, `spring-boot-starter-cache`, `caffeine` |
| `lightweight/src/main/java/.../TbmqLightweightApplication.java` | Spring Boot entry point | VERIFIED | `@SpringBootConfiguration`, `@EnableAutoConfiguration`, `@ComponentScan`, `spring.config.name=tbmq-lightweight` in `updateArguments()` |
| `lightweight/src/main/java/.../storage/rocksdb/DefaultRocksDbStorage.java` | RocksDB lifecycle manager with SmartLifecycle ordering | VERIFIED | `implements RocksDbStorage, SmartLifecycle`; `getPhase()` returns `Integer.MIN_VALUE`; `RocksDB.loadLibrary()` called first; single `RocksDB.open()` call; handles closed before `db.close()` in `stop()` |
| `lightweight/src/main/resources/tbmq-lightweight.yml` | Application configuration with TBMQ_ env var overrides | VERIFIED | `TBMQ_ROCKSDB_PATH:/data/rocksdb`, `TBMQ_MQTT_PORT:1883`, `server.shutdown: "graceful"`, `management.prometheus.metrics.export.enabled: true` |
| `lightweight/src/main/java/.../server/MqttTcpServerBootstrap.java` | Netty TCP server lifecycle with SmartLifecycle phase 0 | VERIFIED | `implements SmartLifecycle`; `getPhase()` returns `0`; `Epoll.isAvailable()` detection; `EpollEventLoopGroup`/`NioEventLoopGroup`; `shutdownGracefully()` in `stop()` |
| `lightweight/src/main/java/.../server/ConnectionCountHandler.java` | Connection tracking and limit enforcement | VERIFIED | `@ChannelHandler.Sharable`; `AtomicInteger connectionCount`; `mqtt.connections.active` Gauge; `ctx.close()` when limit exceeded |
| `lightweight/src/main/java/.../cache/CacheConfiguration.java` | Caffeine cache manager with explicit CaffeineCacheMetrics registration | VERIFIED | `@EnableCaching`; `CaffeineCacheManager`; `recordStats()`; `CaffeineCacheMetrics.monitor()` with Spring Boot-matching tags (per D-16) |
| `lightweight/src/main/java/.../metrics/BrokerMetricsService.java` | Custom broker metric registrations | VERIFIED | `@Service`; `MeterRegistry` injected; `@PostConstruct` registers `mqtt.messages.received.total` counter |
| `lightweight/docker/Dockerfile` | Multi-stage Docker build with eclipse-temurin:17-jre-jammy runtime | VERIFIED | `FROM maven:3.9-eclipse-temurin-17 AS build`; `FROM eclipse-temurin:17-jre-jammy`; alpine warning comment present; `VOLUME ["/data/rocksdb"]`; `EXPOSE 1883 8083`; `HEALTHCHECK` with `actuator/health`; `-XX:MaxRAMPercentage=75.0`; `-Dio.netty.leakDetection.level=DISABLED` |
| `lightweight/.dockerignore` | Docker build context exclusions | VERIFIED | Contains `.planning`, `target/`, `.git`, `*.md`, `.idea`, `*.iml` |

---

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `DefaultRocksDbStorage` | `StorageConfiguration` | Constructor injection | WIRED | `private final StorageConfiguration config` — `config.getPath()` used in `start()` |
| `DefaultRocksDbStorage` | `RocksDbColumnFamily enum` | Column family descriptors built from enum | WIRED | `for (RocksDbColumnFamily cf : RocksDbColumnFamily.values())` builds `cfDescriptors`; `cfHandleMap.put(cfValues[i], cfHandles.get(i + 1))` maps handles |
| `MqttTcpServerBootstrap` | `MqttChannelInitializer` | Constructor injection, passed to `ServerBootstrap.childHandler()` | WIRED | `private final MqttChannelInitializer channelInitializer`; `.childHandler(channelInitializer)` in `start()` |
| `MqttChannelInitializer` | `ConnectionCountHandler` | Added to channel pipeline in `initChannel()` | WIRED | `.addLast("connectionCount", connectionCountHandler)` in `initChannel(ch)` |
| `ConnectionCountHandler` | `MeterRegistry` | `Gauge.builder` registers `mqtt.connections.active` gauge | WIRED | `Gauge.builder("mqtt.connections.active", connectionCount, AtomicInteger::get).register(meterRegistry)` in `@PostConstruct initMetrics()` |
| `CacheConfiguration` | `MeterRegistry` | `CaffeineCacheMetrics.monitor()` binds each cache | WIRED | `CaffeineCacheMetrics.monitor(registry, caffeineCache, cacheName, tags)` in `MeterBinder` bean; Spring Boot-matching tag keys prevent Prometheus collision |
| `Dockerfile HEALTHCHECK` | Actuator health endpoint | `curl http://localhost:8083/actuator/health` | WIRED | `HEALTHCHECK ... CMD curl -sf http://localhost:8083/actuator/health || exit 1` |
| `Dockerfile VOLUME` | RocksDB storage path | `/data/rocksdb` matches `StorageConfiguration` default | WIRED | `VOLUME ["/data/rocksdb"]` matches `path: "${TBMQ_ROCKSDB_PATH:/data/rocksdb}"` |
| `Dockerfile` | `pom.xml` | Maven build produces `tbmq-lightweight.jar` copied to runtime stage | WIRED | `RUN mvn package -DskipTests`; `COPY --from=build /app/target/tbmq-lightweight.jar app.jar` matches `<finalName>tbmq-lightweight</finalName>` |

---

### Data-Flow Trace (Level 4)

This phase delivers infrastructure (embedded storage, network server, metrics registry) rather than data-rendering components. There are no UI components or pages that render dynamic data from a data source. Level 4 data-flow trace is not applicable for this phase.

The one potentially hollow item — `mqtt.messages.received.total` counter in `BrokerMetricsService` — is registered but never incremented. This is documented as a known stub in both summaries (wired in Phase 2 when MQTT PUBLISH handler arrives). It does not affect Phase 1 goal achievement since no Phase 1 truth depends on message counting.

---

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| Test suite (all 21 tests) | `mvn test` in `lightweight/` | 21 tests, 0 failures, 0 errors, BUILD SUCCESS | PASS |
| RocksDB persistence across restart | `RocksDbStorageTest.dataPersistedAcrossRestarts` | Stop, re-open same path, `persistValue` retrieved | PASS |
| Netty TCP accept | `NettyServerBootstrapTest.testTcpConnect_succeeds` | Socket connects to random port | PASS |
| Connection limit enforcement | `NettyServerBootstrapTest.testConnectionLimit_rejectsExcessConnections` | 4th socket read returns -1 | PASS |
| Prometheus endpoint — JVM+custom+cache metrics | `GracefulShutdownTest.testPrometheusEndpoint_returnsMetrics` | HTTP 200, body contains `jvm_memory_used_bytes`, `mqtt_connections_active`, `cache_gets_total` | PASS |
| Health endpoint UP | `GracefulShutdownTest.testHealthEndpoint_returnsUp` | HTTP 200, body contains `"status":"UP"` | PASS |
| Docker image exists | `docker images thingsboard/tbmq-lightweight` | Image `test` tag (369MB) present | PASS |
| ARM64 Docker build | Docker buildx with `--platform linux/arm64` | Local buildx has no arm64 QEMU registered | SKIP — route to human |

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| INFR-02 | 01-01 | Broker uses RocksDB as embedded key-value store for durable state (credentials, ACLs), replacing PostgreSQL | SATISFIED | `DefaultRocksDbStorage` with 4 column families (CREDENTIALS, ACL_RULES, RETAINED_MESSAGES, METADATA); SmartLifecycle lifecycle; persistence confirmed by `dataPersistedAcrossRestarts` test |
| INFR-03 | 01-02 | Broker uses in-process cache (Caffeine) for hot-path lookups, replacing Redis/Valkey | SATISFIED | `CacheConfiguration` with `CaffeineCacheManager`; 3 pre-registered caches; `recordStats()`; `CaffeineCacheMetrics.monitor()` per D-16; confirmed by `CacheConfigurationTest` |
| OPS-02 | 01-03 | Broker ships as a single Docker image that starts with `docker run -p 1883:1883` with no external dependencies | SATISFIED | Dockerfile uses multi-stage build; no docker-compose or external services required; human-approved checkpoint confirmed container starts from single `docker run` with health UP |
| OPS-03 | 01-03 | Docker image supports both linux/amd64 and linux/arm64 architectures | PARTIAL | Dockerfile uses glibc-based `eclipse-temurin:17-jre-jammy` (not alpine/musl — hard ARM64 blocker avoided); `maven:3.9-eclipse-temurin-17` build stage is multi-arch capable; amd64 image human-verified (369MB, starts, accepts connections); arm64 validation deferred — local buildx has no arm64 QEMU |
| OPS-04 | 01-01, 01-03 | Broker persists RocksDB data to a volume-mountable path so configuration survives container restarts | SATISFIED | `VOLUME ["/data/rocksdb"]` in Dockerfile; `TBMQ_ROCKSDB_PATH:/data/rocksdb` config default; human checkpoint verified container restart with same volume preserves data |
| OPS-05 | 01-02 | Broker starts and accepts connections within seconds on a standard developer machine | SATISFIED | Test startup completes in under 2.4 seconds (`CacheConfigurationTest` 2.370s, `GracefulShutdownTest` 0.868s); human checkpoint confirmed container starts within seconds |
| OPS-06 | 01-02 | Broker shuts down gracefully — disconnects active clients, flushes RocksDB, releases native resources without JVM crash | SATISFIED | `shutdownGracefully(0, 5, SECONDS)` in `MqttTcpServerBootstrap.stop()`; CF handles closed before `db.close()` in `DefaultRocksDbStorage.stop()`; `spring.lifecycle.timeout-per-shutdown-phase: "10s"`; human checkpoint confirmed clean shutdown logs, no UnsatisfiedLinkError |

---

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|---|---|---|---|---|
| `BrokerMetricsService.java` | 39 | `mqtt.messages.received.total` counter registered but never incremented | Info | Intentional — documented stub for Phase 2 MQTT PUBLISH handler. Does not affect Phase 1 goals. |
| `MqttChannelInitializer.java` | 34 | `IdleStateHandler(0, 0, 0)` — all timeouts disabled | Info | Intentional — placeholder for Phase 2 keep-alive. Commented in code and Javadoc. Does not affect Phase 1 goals. |
| `MqttChannelInitializer.java` | 37 | Comment: "Phase 2 will add: MqttDecoder, MqttEncoder, MqttSessionHandler" | Info | Forward reference, not a code smell. Phase 1 only needed TCP accept, not MQTT decode. |

No blocker or warning anti-patterns found. All flagged items are intentional, documented, and plan-scoped stubs that do not impact Phase 1 goal achievement.

---

### Human Verification Required

#### 1. ARM64 Container Validation (OPS-03)

**Test:** On a machine with Docker buildx + arm64 QEMU (or native ARM64 hardware), run:
```bash
cd lightweight
docker buildx build --platform linux/arm64 -f docker/Dockerfile -t thingsboard/tbmq-lightweight:arm64-test --load .
docker run --rm -d --name tbmq-arm64-test -p 1883:1883 -p 8083:8083 thingsboard/tbmq-lightweight:arm64-test
docker logs tbmq-arm64-test 2>&1 | grep -E "RocksDB opened|MQTT TCP listener|Started TBMQ"
curl -sf http://localhost:8083/actuator/health
docker stop tbmq-arm64-test
```
**Expected:** Build succeeds, container starts (no `UnsatisfiedLinkError` from RocksDB JNI), health returns `{"status":"UP"}`, clean shutdown
**Why human:** Local buildx (`multiarch` builder) reports only `linux/amd64 (+3), linux/386` platforms — no arm64 QEMU registered. The Dockerfile correctly uses `eclipse-temurin:17-jre-jammy` (glibc, Ubuntu 22.04) which is the prerequisite for RocksDB JNI on ARM64. The structural prerequisite is in place; only runtime validation on arm64 hardware is missing.

---

### Gaps Summary

No hard gaps blocking Phase 1 goal. The only outstanding item is OPS-03 arm64 runtime validation, which cannot be done programmatically on this machine. The Dockerfile architecture decision (glibc base image, warning comment prohibiting alpine) is correct and already addresses the primary known failure mode for ARM64.

All 12 automatically verifiable truths pass. All 7 requirement IDs claimed in the plans are either fully satisfied or partially satisfied with a clear human-verifiable path forward.

---

_Verified: 2026-04-03T15:15:00Z_
_Verifier: Claude (gsd-verifier)_
