# Stack Research

**Domain:** Lightweight embedded MQTT broker (Java/JVM, single Docker image)
**Researched:** 2026-04-02
**Confidence:** MEDIUM-HIGH (core choices HIGH; Docker multi-arch for RocksDB MEDIUM due to historical arm64 gap in older releases)

---

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Java | 17 LTS | Runtime | Minimum per project brief; Virtual Threads (21) not required for R1 since Netty already uses event-loop, not one-thread-per-connection. Java 17 maximises compatibility with edge ARM devices that may lag on JVM updates. |
| Spring Boot | 3.5.x (latest: 3.5.9) | DI container, lifecycle, metrics endpoint | Aligns with existing TBMQ codebase — shared protocol logic, same Spring context model, familiar team toolchain. Critical advantage: shared library extraction later is trivial. Overhead concerns are overstated at broker scale; Spring Boot 3.5 starts in ~2-3 s on Debian slim. |
| Netty | 4.1.x (latest: 4.1.128.Final) | TCP/WebSocket server, MQTT codec | Current TBMQ uses Netty 4.1. Netty 4.2.0 was released April 2025 and has breaking changes (NioEventLoopGroup deprecated, codec module split). Stay on 4.1.x for R1 to keep parity with existing TBMQ codebase and avoid migration risk. |
| LMAX Disruptor | 4.0.0 | In-process inter-session message dispatch | 4-10x throughput over ArrayBlockingQueue. Eliminates lock contention on the hot publish→dispatch path. Already battle-tested in financial messaging (LMAX, Aeron). Ideal for single-producer-many-consumer (publish-to-subscribers) topology. Requires Java 11+. |
| RocksDB (rocksdbjni) | 9.x (latest stable: 9.7.4; v10.10.1 also available) | Embedded KV store for credentials, ACLs | LSM-tree optimised for write-heavy KV workloads. Sub-millisecond reads at 10K+ QPS. Already in ThingsBoard ecosystem. Includes glibc aarch64 `.so` since v6.29.x. Prefer 9.7.4 for stability; v10.x is very fresh (Dec 2025). |
| Caffeine | 3.2.3 | In-process session/subscription cache | Window TinyLfu policy delivers near-optimal hit rates. Outperforms Guava Cache under high-concurrency reads by 2-5x. Spring Boot's official default cache provider since Spring 5. Zero external dependency. |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Micrometer | 1.13.x (managed by Spring Boot 3.5) | Prometheus metrics endpoint | Use with `spring-boot-actuator` — provides `/actuator/prometheus` with zero extra code. Satisfies "basic Prometheus-compatible metrics endpoint" requirement. |
| Bouncy Castle | 1.78.x (bcprov-jdk18on) | X.509 cert parsing for mutual TLS auth | Required for client certificate authentication flow. Use `jdk18on` variant — `jdk15on` is incompatible with Netty 4.2+ and being phased out. |
| Netty tcnative-boringssl-static | 2.0.x | Native SSL/TLS termination | Faster TLS than JDK SSL. Statically linked BoringSSL — no OpenSSL version dependency. Required for production-grade TLS at scale. |
| Logback | 1.5.x (managed by Spring Boot) | Structured logging | Use JSON appender for Docker log aggregation (`logstash-logback-encoder` or native Logback JSON). |
| JMH (test scope) | 1.37 | Microbenchmarks for dispatch path | Validate Disruptor vs BlockingQueue under realistic load during development. |

### Development & Build Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Maven or Gradle | Build | Match whichever TBMQ parent repo uses. If extracting a shared library module, Maven BOM approach aligns with Spring ecosystem. |
| Docker Buildx | Multi-arch image builds | Use `--platform linux/amd64,linux/arm64` with QEMU or native Docker Build Cloud nodes. Required because RocksDB JNI ships glibc arm64 `.so` — works on Debian/Ubuntu base, fails on Alpine. |
| eclipse-temurin:17-jre-jammy | Docker base image | Debian-based (glibc). ~190 MB. RocksDB JNI is incompatible with Alpine (musl). JRE-only image — JDK not needed at runtime. Multi-arch manifest available for amd64 + arm64. |
| GitHub Actions / CI | Multi-arch build pipeline | Use `docker/setup-buildx-action` + `docker/setup-qemu-action` for QEMU emulation, or register native ARM64 runner for faster builds. |

---

## Installation

```xml
<!-- Core Spring Boot parent -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.9</version>
</parent>

<!-- Netty — stay on 4.1.x, override Spring Boot's managed version if needed -->
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-all</artifactId>
    <version>4.1.128.Final</version>
</dependency>

<!-- LMAX Disruptor -->
<dependency>
    <groupId>com.lmax</groupId>
    <artifactId>disruptor</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- RocksDB JNI -->
<dependency>
    <groupId>org.rocksdb</groupId>
    <artifactId>rocksdbjni</artifactId>
    <version>9.7.4</version>
</dependency>

<!-- Caffeine cache -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>3.2.3</version>
</dependency>

<!-- Spring Boot actuator for metrics -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- Micrometer Prometheus registry -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- Netty native TLS (optional but recommended) -->
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-tcnative-boringssl-static</artifactId>
    <version>2.0.70.Final</version>
    <classifier>linux-x86_64</classifier>
</dependency>
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-tcnative-boringssl-static</artifactId>
    <version>2.0.70.Final</version>
    <classifier>linux-aarch_64</classifier>
</dependency>
```

---

## Decision Rationale — Key Areas

### 1. In-Process Message Dispatch: Disruptor over BlockingQueue

**Recommendation: LMAX Disruptor 4.0.0**

Benchmarks (hardware-dependent, JMH-measured):
- ArrayBlockingQueue: ~2-3M ops/sec under contention
- LMAX Disruptor (single-producer, multiple consumers): 25-50M ops/sec; 3-10x real-world advantage

The MQTT publish→dispatch path is exactly the workload Disruptor was designed for: one Netty I/O thread produces a message event, multiple subscriber handler threads consume it. The ring buffer is pre-allocated, eliminating GC pressure on the hot path. Cache-line padding eliminates false sharing.

**BlockingQueue is acceptable for R1** if Disruptor complexity is a concern: `LinkedBlockingQueue` works fine at <10K concurrent publishers. Use BlockingQueue as the initial implementation and swap to Disruptor if benchmarks show saturation. Both fit behind the same `MessageDispatcher` interface.

**What to avoid:** `java.util.concurrent.Executors.newCachedThreadPool()` as the dispatch mechanism — unbounded thread creation under spike load is a reliability risk.

### 2. Embedded Storage: RocksDB over H2 / Chronicle Map / MapDB

**Recommendation: RocksDB (rocksdbjni 9.7.4)**

| Criterion | RocksDB | H2 | Chronicle Map | MapDB |
|-----------|---------|-----|---------------|-------|
| Write throughput | Very high (LSM) | Medium (SQL overhead) | Very high (off-heap) | Low-Medium |
| Read latency | Sub-ms | Sub-ms (in-memory mode) | Sub-ms | Medium |
| Java-native | No (JNI) | Yes | Yes (Unsafe) | Yes |
| Persistence | Yes | Yes | Yes | Yes |
| Maintenance status | Active (Facebook/Meta) | Active | Active | Slow (low commit velocity 2023-2024) |
| ARM64 support | Yes (since 6.29.x) | Yes | Yes | Yes |
| ThingsBoard ecosystem | Yes | No | No | No |
| Alpine Docker compat. | No (glibc only) | Yes | Yes | Yes |

**Why RocksDB wins:** The project brief has already committed to RocksDB for credentials and ACLs. It is used in the broader ThingsBoard ecosystem, meaning operational familiarity exists. For the workload (read-heavy credentials/ACL lookups + write of retained messages), RocksDB's LSM tree delivers excellent throughput without SQL overhead.

**H2 is not suitable for this role:** H2 is a SQL database optimised for transactional relational workloads. For a simple KV mapping of `clientId → credentials`, H2 adds JDBC overhead, connection pooling complexity, and SQL parsing on every access. H2's "embedded mode" still operates as a full RDBMS.

**Chronicle Map is not suitable:** Chronicle Map provides off-heap mmap-based concurrent hash maps. It excels at in-memory shared-memory IPC but is not designed for durable on-disk append-write workloads. Restart durability would require explicit serialisation. Its `sun.misc.Unsafe` dependency is a long-term JVM compatibility risk.

**MapDB is not suitable:** MapDB showed low commit velocity in 2023-2024, meaning it is effectively in maintenance-only mode. Unaddressed open issues indicate the project cannot be relied upon for production use.

### 3. In-Process Cache: Caffeine

**Recommendation: Caffeine 3.2.3**

Caffeine is the official replacement for Guava Cache in Spring Boot (default since Spring 5). Under high concurrency (hundreds of threads), Caffeine's W-TinyLFU eviction policy outperforms Guava's synchronised LRU by 2-5x in throughput. For TBMQ Lightweight's use case (caching active session state, subscription lookups, recently authenticated clients), Caffeine's asynchronous refresh and automatic eviction on size/time are exactly what is needed.

**Guava Cache: do not use.** It is deprecated for caching in Spring and has no advantages over Caffeine. Spring's `@Cacheable` with Caffeine requires zero boilerplate.

### 4. Netty Configuration for MQTT at Scale

**Stay on Netty 4.1.128.Final for R1.**

Rationale: TBMQ already runs on Netty 4.1.x. Netty 4.2.0 (released April 2025) introduces breaking changes relevant to MQTT brokers:
- `NioEventLoopGroup` deprecated; replaced by `MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())`
- `netty-codec` split into sub-modules (affects MQTT codec dependency graph)
- Default buffer allocator changed from `pooled` to `adaptive`
- Pipeline call-stack flattened (impacts concurrent pipeline modification)

These are non-trivial to absorb during initial port from TBMQ. Adopt Netty 4.2 in a dedicated phase after R1 ships.

**Key tuning for high-concurrency MQTT on Netty 4.1:**
- Use `EpollEventLoopGroup` on Linux (edge-triggered, avoids JDK NIO busy-wait bug)
- Boss group: 1 thread (accepts connections only)
- Worker group: `2 * nCPU` threads (handles I/O)
- Set `SO_BACKLOG` to 1024 for burst connection acceptance
- Set `SO_RCVBUF` / `SO_SNDBUF` to 32KB for high-volume publish clients
- Use `PooledByteBufAllocator.DEFAULT` on worker group — reduces GC on message decode
- `ChannelOption.AUTO_READ` = true; let Netty drive backpressure via channel writability

### 5. Dependency Injection: Spring Boot (not lightweight alternative)

**Recommendation: Spring Boot 3.5.x**

Despite the appeal of Micronaut or Quarkus for lower memory footprint, Spring Boot is the correct choice for TBMQ Lightweight:

1. **Code reuse**: TBMQ's existing protocol logic (MQTT state machines, session management) is written as Spring beans. Extracting this to a shared library is only viable if both projects share the same DI container.
2. **Team familiarity**: The engineering team knows Spring Boot deeply. A framework switch adds cognitive overhead with no protocol benefit.
3. **Actuator metrics**: Spring Boot Actuator + Micrometer provides the Prometheus endpoint at zero cost.
4. **Memory footprint is acceptable**: A well-tuned Spring Boot 3.5 application on `eclipse-temurin:17-jre-jammy` with `-XX:+UseSerialGC -XX:MaxRAMPercentage=75` runs in 150-250 MB heap for a broker serving thousands of connections. This is not a serverless function where 100 ms cold starts matter.

**Micronaut and Quarkus** provide real advantages (50 ms starts, 65-75 MB RSS in native mode) but require rewriting the existing TBMQ protocol logic and introduce new build-time DI constraints. Not worth the cost for R1.

### 6. Docker Multi-Arch Strategy

**Recommendation: Debian base + Docker Buildx + QEMU for CI, native builders for release**

The critical constraint is RocksDB JNI:
- RocksDB JNI ships `librocksdbjni-linux64.so` (glibc x86_64) and `librocksdbjni-linux-aarch64.so` (glibc arm64) in the fat jar from 6.29.x onwards.
- **Alpine Linux (musl) is incompatible with RocksDB JNI.** Attempts to use `gcompat` result in segfaults. This eliminates Alpine as a base image.
- Use `eclipse-temurin:17-jre-jammy` (Ubuntu Jammy, glibc). Supports both `linux/amd64` and `linux/arm64`.

**Build approach:**
```dockerfile
# Multi-stage: build on JDK, ship on JRE
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app
COPY . .
RUN ./mvnw -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/tbmq-lightweight.jar app.jar
EXPOSE 1883 8883 8084
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

```bash
# CI/CD: QEMU emulation (slower but simple)
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --push \
  -t thingsboard/tbmq-lightweight:latest .
```

For release pipelines with many concurrent builds, register a native ARM64 runner (GitHub Actions `ubuntu-24.04-arm`) to avoid QEMU emulation overhead for the arm64 slice.

---

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| LMAX Disruptor 4.0 | LinkedBlockingQueue | If dispatch topology is complex (many fanout paths), BlockingQueue with per-subscriber queues can be simpler; acceptable for <10K publishers |
| RocksDB 9.7.4 | SQLite via sqlite-jdbc | If full SQL query capability over credentials becomes a requirement (e.g., complex ACL patterns), SQLite is a reasonable swap — but not needed in R1 |
| RocksDB 9.7.4 | H2 (in-memory + file persistence) | Never for this role — H2 is a RDBMS, not a KV store; adds SQL overhead with no benefit |
| Caffeine 3.2.3 | ConcurrentHashMap | Acceptable if eviction and size bounding are not needed; but session state MUST be bounded to prevent OOM at high connection counts |
| Spring Boot 3.5.x | Plain Spring Framework + Spring Core | If team wants to eliminate autoconfigure overhead; viable but loses Actuator convenience. Not recommended for R1. |
| Netty 4.1.x | Vert.x | Vert.x wraps Netty and adds an event bus abstraction; adds indirection over raw Netty without benefit for a protocol server that owns its own channel pipeline |
| eclipse-temurin:17-jre-jammy | eclipse-temurin:21-jre-jammy | Upgrade to Java 21 if Virtual Threads are needed in R2 (e.g., for blocking RocksDB calls on the session path). Java 21 is the natural next step. |

---

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| Alpine Linux Docker base | musl libc is incompatible with RocksDB JNI glibc binaries; causes UnsatisfiedLinkError or segfaults | eclipse-temurin:17-jre-jammy (Debian/Ubuntu, glibc) |
| MapDB | Low commit velocity 2023-2024; unaddressed open issues; not suitable for production embedded KV | RocksDB (rocksdbjni) |
| Chronicle Map | Off-heap mmap hash map, not a durable KV store; `sun.misc.Unsafe` dependency is a Java 21+ risk | RocksDB for durability; Caffeine for in-memory cache |
| H2 as KV store | Full RDBMS semantics add JDBC, SQL parsing, connection pool overhead for simple credential lookups | RocksDB |
| Guava Cache | Deprecated for Spring caching since Spring 5; slower than Caffeine under concurrency | Caffeine 3.2.3 |
| Netty 4.2.x in R1 | Breaking API changes (NioEventLoopGroup deprecated, codec split) create unnecessary migration risk before first ship | Netty 4.1.128.Final; plan 4.2 upgrade for post-R1 |
| newCachedThreadPool() as dispatch mechanism | Unbounded thread creation causes thread storms under spike load | LMAX Disruptor or bounded BlockingQueue |
| RocksDB v10.x for R1 | Released Dec 2025, very fresh; insufficient production track record | RocksDB 9.7.4 — stable 9.x line |

---

## Version Compatibility

| Component | Compatible With | Notes |
|-----------|-----------------|-------|
| Spring Boot 3.5.9 | Java 17, 21 | Requires Java 17 minimum |
| Netty 4.1.128.Final | Java 8+ | Compatible with Spring Boot 3.5 managed version; pin explicitly to avoid upgrades to 4.2 |
| RocksDB rocksdbjni 9.7.4 | Java 8+; glibc Linux, macOS, Windows x64 | ARM64 glibc `.so` included since 6.29.x; will NOT work on Alpine (musl) |
| LMAX Disruptor 4.0.0 | Java 11+ | Removed WorkerPool; use ThreadFactory constructor, not Executor |
| Caffeine 3.2.3 | Java 11+ | Spring Boot 3.5 manages Caffeine version; explicit version override not needed |
| eclipse-temurin:17-jre-jammy | linux/amd64, linux/arm64 | Multi-arch manifest on Docker Hub; glibc present |
| netty-tcnative-boringssl-static | Must match Netty 4.1.x | Use platform classifier `linux-x86_64` and `linux-aarch_64` in multi-arch builds |

---

## Stack Patterns by Scenario

**If message dispatch saturation occurs before tens of thousands of connections:**
- Profile first. If the Disruptor ring buffer size is too small (power of 2, e.g., 65536), increase it.
- Ensure Disruptor producer strategy is `SingleProducerSequencer` when only one Netty I/O thread publishes per connection group.

**If RocksDB startup latency is a concern (cold Docker start):**
- Use RocksDB column families to partition credentials, ACLs, and retained messages.
- Open all column families in a single `RocksDB.open()` call at startup — reduces JNI overhead vs multiple opens.
- Pre-warm RocksDB block cache at startup with a configurable warm-up scan.

**If edge deployments need smaller images:**
- Use `jlink` to create a custom JRE with only required modules (`java.base`, `java.net`, `java.logging`, etc.). Can reduce JRE from ~200 MB to ~50-70 MB.
- But: `jlink` + RocksDB JNI requires careful module path configuration — validate on both amd64 and arm64 before committing to this optimization.

**If Java 21 upgrade is pursued in R2:**
- Virtual Threads (`-Dspring.threads.virtual.enabled=true` in Spring Boot 3.2+) are attractive for potentially blocking RocksDB reads on the session path.
- LMAX Disruptor is compatible with Java 21; no changes needed.
- Migrate to `eclipse-temurin:21-jre-jammy` base image (same glibc compatibility guarantees).

---

## Sources

- LMAX Disruptor GitHub Releases — version 4.0.0 confirmed (September 2023); Java 11+ minimum: https://github.com/LMAX-Exchange/disruptor/releases
- LMAX Disruptor performance paper — 4-10x advantage over ArrayBlockingQueue: https://lmax-exchange.github.io/disruptor/disruptor.html
- LMAX Disruptor in-process event broker benchmark (7.58M dispatches/sec, Intel Core Ultra 7): https://dev.to/axelncho/using-lmax-disruptor-to-build-a-high-performance-in-memory-event-broker-in-java-6i
- RocksDB GitHub releases — v9.7.4 (Oct 2024), v10.10.1 (Feb 2026): https://github.com/facebook/rocksdb/releases
- RocksDB Java JNI basics (official wiki): https://github.com/facebook/rocksdb/wiki/RocksJava-Basics
- RocksDB ARM64 issue + resolution history: https://github.com/facebook/rocksdb/issues/5559
- RocksDB Alpine musl incompatibility (confirmed segfault on alpine, recommend Debian): https://github.com/docker-flink/docker-flink/issues/14
- Maven Repository: rocksdbjni (9.2.1 and 9.7.4 confirmed): https://mvnrepository.com/artifact/org.rocksdb/rocksdbjni
- Caffeine GitHub releases — v3.2.3 latest (Oct 2024): https://github.com/ben-manes/caffeine/releases
- Caffeine vs Guava benchmark — W-TinyLFU superior hit rate and concurrency: https://github.com/ben-manes/caffeine/wiki/Benchmarks
- Netty 4.1.128.Final release (Oct 2025): https://netty.io/news/2025/10/14/4-1-128-Final.html
- Netty 4.2.0.Final release (Apr 2025) + migration guide: https://netty.io/wiki/netty-4.2-migration-guide.html
- Spring Boot 3.5.9 release (Dec 2025): https://spring.io/blog/2025/12/18/spring-boot-3-5-9-available-now/
- TBMQ architecture (Netty + Spring Boot + actor system): https://thingsboard.io/docs/mqtt-broker/architecture/
- Docker multi-platform build docs: https://docs.docker.com/build/building/multi-platform/
- eclipse-temurin Docker Hub (multi-arch manifest amd64 + arm64): https://hub.docker.com/_/eclipse-temurin
- MapDB maintenance status assessment (low velocity 2024): https://marekhudyma.com/java/2024/07/01/mapdb.html

---

*Stack research for: TBMQ Lightweight — embedded MQTT broker (Java, single Docker image)*
*Researched: 2026-04-02*
