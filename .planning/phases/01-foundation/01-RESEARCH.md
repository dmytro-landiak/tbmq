# Phase 1: Foundation - Research

**Researched:** 2026-04-03
**Domain:** Spring Boot application skeleton, RocksDB JNI lifecycle, Netty TCP bootstrap, Micrometer/Prometheus metrics, multi-arch Docker (amd64 + arm64)
**Confidence:** HIGH — all library choices are locked decisions from CONTEXT.md, versions verified against Maven Central as of 2026-04-03.

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Project Structure**
- D-01: Flat modular Maven structure — single root POM with package-level separation (not TBMQ's deep multi-module hierarchy). Packages: `org.thingsboard.mqtt.broker.lightweight.server` (Netty bootstrap), `org.thingsboard.mqtt.broker.lightweight.storage` (RocksDB), `org.thingsboard.mqtt.broker.lightweight.config` (Spring config), `org.thingsboard.mqtt.broker.lightweight.metrics` (Prometheus).
- D-02: Start as a fully self-contained repo with local code — no shared library extraction from TBMQ in R1. Copy-first, extract-later.
- D-03: Follow TBMQ naming conventions (interface + `Default` prefix implementation in application code, `Impl` suffix for DAO layer, `Entity` suffix for persistence models).

**RocksDB Schema Design**
- D-04: One RocksDB column family per entity type: `credentials`, `acl_rules`, `retained_messages`, `metadata`. The `metadata` CF stores a `schema_version` key for R2 migration compatibility.
- D-05: Keys are UTF-8 strings. Values are JSON-serialized (Jackson) for readability and debuggability in R1.
- D-06: All RocksDB objects (`RocksDB`, `ColumnFamilyHandle`, `Options`, `WriteOptions`, `ReadOptions`) MUST be explicitly closed — finalizers were removed in RocksDB v7+. Use try-with-resources or Spring `@PreDestroy`.
- D-07: RocksDB lifecycle managed via Spring `SmartLifecycle` with explicit phase ordering — RocksDB starts first (lowest phase), stops last (highest phase).

**Netty Bootstrap**
- D-08: Boss/worker thread model: 1 boss thread, worker threads = available CPU cores. Use native Epoll on Linux with NIO fallback.
- D-09: Channel pipeline in Phase 1 is minimal: `IdleStateHandler` (keep-alive placeholder) + connection counter handler. MQTT codec added in Phase 2.
- D-10: Connection limit configurable via environment variable (default: 10,000). Reject new connections with channel close when limit reached.

**Docker and Deployment**
- D-11: Docker image: `thingsboard/tbmq-lightweight`, base: `eclipse-temurin:17-jre-jammy` (NOT Alpine — RocksDB JNI is musl-incompatible). Multi-stage build.
- D-12: Persistent data path: `/data/rocksdb` inside the container.
- D-13: Environment variable convention: `TBMQ_` prefix for all broker-specific configuration. Spring Boot's relaxed binding maps these to YAML properties.
- D-14: Docker healthcheck: `curl -sf http://localhost:8083/actuator/health || exit 1`.

**Metrics and Observability**
- D-15: Spring Boot Actuator + Micrometer with Prometheus registry. Endpoint: `/actuator/prometheus`. Include JVM metrics and custom broker metrics (connection count, RocksDB read/write latency).
- D-16: Caffeine cache metrics exposed via Micrometer's `CaffeineCacheMetrics`.

**In-Process Cache**
- D-17: Use Caffeine for all in-process caching. Configure via Spring Boot's `spring.cache` properties.

### Claude's Discretion
- Exact Maven artifact IDs and module naming
- Spring Boot configuration property naming (as long as `TBMQ_` prefix convention is followed)
- Specific Netty channel handler class names and package layout
- Prometheus metric names (follow Micrometer conventions)
- Dockerfile COPY ordering and layer optimization

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope.
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| INFR-02 | Broker uses RocksDB as embedded key-value store for durable state (credentials, ACLs), replacing PostgreSQL | D-04 through D-07; RocksDB 9.7.4 confirmed in Maven Central; column family initialization pattern in Architecture Patterns section |
| INFR-03 | Broker uses in-process cache (Caffeine) for hot-path lookups (session state, subscription matching, credential checks), replacing Redis/Valkey | D-17; Caffeine 3.2.1 managed by Spring Boot 3.5.3; Spring Cache integration pattern documented |
| OPS-02 | Broker ships as a single Docker image that starts with `docker run -p 1883:1883 thingsboard/tbmq-lightweight` with no external dependencies | D-11 through D-14; multi-stage Dockerfile pattern; eclipse-temurin:17-jre-jammy confirmed multi-arch |
| OPS-03 | Docker image supports both linux/amd64 and linux/arm64 architectures | D-11; docker buildx + QEMU pattern documented; glibc base requirement enforced |
| OPS-04 | Broker persists RocksDB data to a volume-mountable path so configuration survives container restarts | D-12; `/data/rocksdb` volume pattern; Docker VOLUME instruction |
| OPS-05 | Broker starts and accepts connections within seconds on a standard developer machine | Spring Boot 3.5 startup ~2-3s; RocksDB open all CFs in single call; no external I/O at startup |
| OPS-06 | Broker shuts down gracefully — disconnects active clients, flushes RocksDB, releases native resources without JVM crash | D-06, D-07; SmartLifecycle ordering; graceful shutdown sequence in Architecture Patterns |
</phase_requirements>

---

## Summary

Phase 1 creates a greenfield Spring Boot application in a new repository. The existing TBMQ codebase at the project root is a reference for patterns and conventions only — no code is inherited. The deliverable is a process that starts, opens RocksDB with four column families, binds Netty TCP on port 1883, exposes `/actuator/prometheus`, and packages into a multi-arch Docker image. No MQTT protocol handling is required.

All technology choices are locked. The primary implementation challenge is correctness of lifecycle ordering: RocksDB must start before all services that use it and stop last, and Netty must be bound after RocksDB is ready. Shutdown must flush RocksDB and release JNI native memory before the JVM exits — failing this causes an `UnsatisfiedLinkError` or segfault on shutdown, which counts as a failure for OPS-06.

The secondary challenge is the Docker multi-arch build. The base image `eclipse-temurin:17-jre-jammy` is non-negotiable: Alpine/musl is hard-blocked by RocksDB JNI. The `docker buildx` command with `--platform linux/amd64,linux/arm64` is the standard build mechanism; it works via QEMU emulation for CI environments.

**Primary recommendation:** Implement SmartLifecycle ordering early (Phase = Integer.MIN_VALUE for RocksDB start, Integer.MAX_VALUE for RocksDB stop) before writing any other service. Everything else in this phase depends on the storage layer being initialized first and closed last.

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.5.3 | DI container, lifecycle, web server, metrics endpoint | Locked decision; aligns with existing TBMQ codebase for future shared library extraction. 3.5.3 is latest GA as of 2026-04-03. |
| Netty | 4.1.122.Final | TCP server bootstrap, channel pipeline, connection management | Managed by Spring Boot 3.5.3 at 4.1.122.Final. Locked to 4.1.x — Netty 4.2 has breaking API changes (NioEventLoopGroup deprecated). |
| RocksDB (rocksdbjni) | 9.7.4 | Embedded KV store for credentials, ACLs, schema version | Locked decision. 9.7.4 confirmed in Maven Central. v10.x is available (10.2.1) but too fresh for R1. 9.7.4 provides glibc arm64 native library. |
| Caffeine | 3.2.1 | In-process cache for hot-path lookups | Managed by Spring Boot 3.5.3 at 3.2.1. Locked decision. W-TinyLFU eviction policy. |
| Micrometer | 1.15.1 | Metrics registration, Prometheus format | Managed by Spring Boot 3.5.3 at 1.15.1. Provides `/actuator/prometheus` via spring-boot-actuator. |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| spring-boot-starter-actuator | managed by Spring Boot 3.5.3 | Exposes `/actuator/health`, `/actuator/prometheus` endpoints | Required for OPS-02 healthcheck and OPS-03/OPS-06 metrics |
| micrometer-registry-prometheus | managed by Spring Boot 3.5.3 | Prometheus text-format output | Required to enable Prometheus scraping |
| spring-boot-starter-web | managed by Spring Boot 3.5.3 | Embedded Tomcat for REST/Actuator endpoint (separate from Netty MQTT) | Actuator runs on Tomcat port 8083; Netty handles MQTT on port 1883 |
| lombok | managed by Spring Boot | Boilerplate reduction | Used on all service classes per TBMQ conventions: `@Slf4j`, `@RequiredArgsConstructor`, `@Data` |
| jackson-databind | managed by Spring Boot | JSON serialization of RocksDB values | D-05: values stored as JSON for debuggability |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| eclipse-temurin:17-jre-jammy | alpine-based JRE | Alpine uses musl libc — hard-blocked by RocksDB JNI glibc requirement. Never for this project. |
| RocksDB 9.7.4 | RocksDB 9.11.2 (latest 9.x) | 9.11.2 also available; 9.7.4 is the pinned decision from research phase for stability. Can be evaluated post-R1. |
| Spring Boot 3.5.3 | Spring Boot 3.4.13 (reference codebase) | Reference codebase uses 3.4.13. Lightweight project starts fresh on 3.5.3. Keeping separate avoids forced alignment. |
| Netty 4.1.122.Final | Netty 4.2.1.Final | 4.2 has breaking changes relevant to MQTT brokers; stay on 4.1.x per locked decision. |

**Installation:**
```xml
<!-- pom.xml root — spring-boot-starter-parent manages all versions -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.3</version>
</parent>

<!-- Netty — pin to prevent drift to 4.2.x via dependency resolution -->
<properties>
    <netty.version>4.1.122.Final</netty.version>
</properties>

<!-- RocksDB — NOT managed by Spring Boot; must specify version explicitly -->
<dependency>
    <groupId>org.rocksdb</groupId>
    <artifactId>rocksdbjni</artifactId>
    <version>9.7.4</version>
</dependency>

<!-- Spring Boot Actuator + Prometheus -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- Netty TCP transport — netty.version property pins it -->
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-all</artifactId>
</dependency>

<!-- Caffeine — managed by Spring Boot 3.5.3 at 3.2.1 -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
```

**Version verification:** All versions verified against Maven Central on 2026-04-03:
- Spring Boot 3.5.3: latest GA (`https://search.maven.org`)
- RocksDB 9.7.4: confirmed in 9.x line (latest 9.x is 9.11.2; 9.7.4 pinned per research decision)
- Netty 4.1.122.Final: managed by Spring Boot 3.5.3 BOM
- Caffeine 3.2.1: managed by Spring Boot 3.5.3 BOM
- Micrometer 1.15.1: managed by Spring Boot 3.5.3 BOM

---

## Architecture Patterns

### Recommended Project Structure

```
tbmq-lightweight/                             # New separate repository
├── src/
│   └── main/
│       ├── java/org/thingsboard/mqtt/broker/lightweight/
│       │   ├── TbmqLightweightApplication.java      # Spring Boot entry point
│       │   ├── server/                               # Netty TCP bootstrap
│       │   │   ├── MqttTcpServerBootstrap.java       # SmartLifecycle, starts after storage
│       │   │   ├── MqttChannelInitializer.java       # Pipeline builder (Phase 1: minimal)
│       │   │   └── ConnectionCountHandler.java       # ChannelInboundHandler for connection limit
│       │   ├── storage/
│       │   │   └── rocksdb/
│       │   │       ├── RocksDbStorage.java           # Interface: get/put/delete per CF
│       │   │       ├── DefaultRocksDbStorage.java    # SmartLifecycle, lowest phase
│       │   │       └── RocksDbColumnFamily.java      # Enum: CREDENTIALS, ACL_RULES, RETAINED_MESSAGES, METADATA
│       │   ├── cache/
│       │   │   └── CacheConfiguration.java           # @Configuration, Caffeine CacheManager setup
│       │   ├── config/
│       │   │   ├── NettyConfiguration.java           # @ConfigurationProperties(prefix="tbmq.netty")
│       │   │   ├── StorageConfiguration.java         # @ConfigurationProperties(prefix="tbmq.storage")
│       │   │   └── MetricsConfiguration.java         # Custom meter binders
│       │   └── metrics/
│       │       └── BrokerMetricsService.java         # Connection count gauge, RocksDB latency timer
│       └── resources/
│           ├── tbmq-lightweight.yml                  # Application config with TBMQ_ env var overrides
│           └── logback-spring.xml                    # Logging config
├── docker/
│   └── Dockerfile                                    # Multi-stage: JDK build → JRE runtime
└── pom.xml                                           # Single-module Maven POM
```

**Structure rationale:**
- Single Maven module (flat structure per D-01) — no `application/`, `common/` sub-modules in Phase 1.
- `storage/rocksdb/` is isolated — services call the `RocksDbStorage` interface, enabling future storage backend swaps.
- `config/` mirrors TBMQ's config package pattern — `@ConfigurationProperties` beans with `TBMQ_` env var overrides.
- Phase 1 does not include `service/`, `actors/` — those are Phase 2+.

### Pattern 1: SmartLifecycle for RocksDB Lifecycle Ordering

**What:** `DefaultRocksDbStorage` implements `SmartLifecycle`. It uses `getPhase()` = `Integer.MIN_VALUE` to start before all other beans and `getPhase()` = `Integer.MAX_VALUE` to stop last. Netty's `MqttTcpServerBootstrap` uses `getPhase()` = `0` (default) — guaranteed to start after RocksDB is open and stop before RocksDB flushes.

**When to use:** Any bean with JNI or native resource lifecycle that must start before and stop after all dependent beans.

**Example:**
```java
// Source: Spring SmartLifecycle contract
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultRocksDbStorage implements RocksDbStorage, SmartLifecycle {

    private final StorageConfiguration config;
    private volatile boolean running = false;
    private RocksDB db;
    private final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE; // starts first among SmartLifecycle beans
    }

    @Override
    public void start() {
        RocksDB.loadLibrary(); // MUST be called before any RocksDB operation
        // Open all column families in a single call
        List<ColumnFamilyDescriptor> cfDescriptors = List.of(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor("credentials".getBytes(StandardCharsets.UTF_8)),
            new ColumnFamilyDescriptor("acl_rules".getBytes(StandardCharsets.UTF_8)),
            new ColumnFamilyDescriptor("retained_messages".getBytes(StandardCharsets.UTF_8)),
            new ColumnFamilyDescriptor("metadata".getBytes(StandardCharsets.UTF_8))
        );
        DBOptions dbOptions = new DBOptions()
            .setCreateIfMissing(true)
            .setCreateMissingColumnFamilies(true);
        try {
            db = RocksDB.open(dbOptions, config.getPath(), cfDescriptors, cfHandles);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to open RocksDB at " + config.getPath(), e);
        }
        running = true;
        log.info("RocksDB opened at {}", config.getPath());
    }

    @Override
    public void stop() {
        // Close column family handles BEFORE closing the database
        cfHandles.forEach(ColumnFamilyHandle::close);
        if (db != null) {
            db.close(); // flushes memtable, closes WAL
        }
        running = false;
        log.info("RocksDB closed");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
```

### Pattern 2: Netty TCP Bootstrap with Epoll/NIO Detection

**What:** `MqttTcpServerBootstrap` implements `SmartLifecycle` (phase = 0, after RocksDB). Uses `EpollEventLoopGroup` + `EpollServerSocketChannel` on Linux and falls back to `NioEventLoopGroup` + `NioServerSocketChannel` elsewhere.

**When to use:** Always for Netty TCP server on Linux where Epoll is available — Epoll uses edge-triggered I/O and avoids JDK NIO's busy-wait issue.

**Example:**
```java
// Source: existing TBMQ AbstractMqttServerBootstrap pattern + Epoll detection
@Service
@RequiredArgsConstructor
@Slf4j
public class MqttTcpServerBootstrap implements SmartLifecycle {

    private final NettyConfiguration config;
    private final MqttChannelInitializer channelInitializer;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;

    @Override
    public int getPhase() {
        return 0; // starts after RocksDB (Integer.MIN_VALUE), stops before it
    }

    @Override
    public void start() {
        boolean epollAvailable = Epoll.isAvailable();
        bossGroup = epollAvailable
            ? new EpollEventLoopGroup(1)
            : new NioEventLoopGroup(1);
        workerGroup = epollAvailable
            ? new EpollEventLoopGroup(Runtime.getRuntime().availableProcessors())
            : new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());

        Class<? extends ServerChannel> channelClass = epollAvailable
            ? EpollServerSocketChannel.class
            : NioServerSocketChannel.class;

        ServerBootstrap bootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(channelClass)
            .option(ChannelOption.SO_BACKLOG, 1024)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .childHandler(channelInitializer);
        try {
            serverChannel = bootstrap.bind(config.getPort()).sync().channel();
            log.info("MQTT TCP listener started on port {}", config.getPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Netty bind interrupted", e);
        }
        running = true;
    }

    @Override
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        running = false;
        log.info("MQTT TCP listener stopped");
    }

    @Override
    public boolean isRunning() { return running; }
}
```

### Pattern 3: Minimal Phase 1 Channel Pipeline

**What:** Phase 1 pipeline has no MQTT codec. It establishes the pattern that Phase 2 will extend. The pipeline contains: `IdleStateHandler` (reader timeout = 0 = disabled in Phase 1) + `ConnectionCountHandler` (enforces connection limit).

**When to use:** Phase 1 only. Phase 2 inserts `MqttDecoder`, `MqttEncoder`, and `MqttSessionHandler` before `ConnectionCountHandler`.

**Example:**
```java
// Source: adapted from existing TBMQ MqttTcpChannelInitializer
@Component
@RequiredArgsConstructor
@Slf4j
public class MqttChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final ConnectionCountHandler connectionCountHandler;

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        // Phase 1: placeholder idle handler (disabled — keepAlive=0 means no timeout)
        pipeline.addLast("idle", new IdleStateHandler(0, 0, 0));
        // Phase 1: connection count enforcement + simple echo rejection at limit
        pipeline.addLast("connectionCount", connectionCountHandler);
        // Phase 2 will add: MqttDecoder, MqttEncoder, MqttSessionHandler
    }
}
```

### Pattern 4: Connection Count Handler (Shared Handler, @ChannelHandler.Sharable)

**What:** A `@ChannelHandler.Sharable` `ChannelInboundHandlerAdapter` that tracks active connection count via an `AtomicInteger`. Increments on `channelActive`, decrements on `channelInactive`. Closes new channels when limit is exceeded.

**When to use:** Required by D-10 — connection limit enforcement without MQTT-layer code.

**Example:**
```java
// Implements D-10: configurable connection limit
@Component
@ChannelHandler.Sharable
@RequiredArgsConstructor
@Slf4j
public class ConnectionCountHandler extends ChannelInboundHandlerAdapter {

    private final NettyConfiguration config;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger connectionCount = new AtomicInteger(0);

    @PostConstruct
    public void initMetrics() {
        Gauge.builder("mqtt.connections.active", connectionCount, AtomicInteger::get)
            .description("Current number of active MQTT connections")
            .register(meterRegistry);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        int current = connectionCount.incrementAndGet();
        if (current > config.getMaxConnections()) {
            connectionCount.decrementAndGet();
            log.warn("Connection limit {} reached; closing new connection from {}",
                config.getMaxConnections(), ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        connectionCount.decrementAndGet();
        super.channelInactive(ctx);
    }
}
```

### Pattern 5: Spring Boot Configuration with TBMQ_ Env Prefix

**What:** `@ConfigurationProperties` beans with `TBMQ_` prefix environment variables. Spring Boot's relaxed binding converts `TBMQ_NETTY_PORT=1883` to `tbmq.netty.port=1883`.

**When to use:** All broker-specific configuration per D-13.

**Example (YAML):**
```yaml
# tbmq-lightweight.yml
tbmq:
  netty:
    port: "${TBMQ_MQTT_PORT:1883}"
    max-connections: "${TBMQ_MAX_CONNECTIONS:10000}"
    boss-threads: "${TBMQ_NETTY_BOSS_THREADS:1}"
    worker-threads: "${TBMQ_NETTY_WORKER_THREADS:0}"  # 0 = availableProcessors()
  storage:
    rocksdb:
      path: "${TBMQ_ROCKSDB_PATH:/data/rocksdb}"

management:
  endpoints:
    web:
      exposure:
        include: "health,info,prometheus"
  endpoint:
    health:
      show-details: "always"

server:
  port: "${TBMQ_HTTP_PORT:8083}"
  shutdown: "graceful"

spring:
  cache:
    type: caffeine
  application:
    name: tbmq-lightweight
  lifecycle:
    timeout-per-shutdown-phase: "10s"
```

**Note on `server.shutdown: graceful`:** This enables Spring Boot's graceful shutdown which coordinates with `SmartLifecycle`. The Netty server and RocksDB both participate via their `stop()` methods. The `spring.lifecycle.timeout-per-shutdown-phase` provides a bounded shutdown window.

### Pattern 6: Prometheus Metrics Exposure

**What:** Spring Boot Actuator + `micrometer-registry-prometheus` provides `/actuator/prometheus` at zero code cost for JVM metrics. Custom broker metrics (connection count, RocksDB latency) are registered via `MeterRegistry`.

**Configuration required in `tbmq-lightweight.yml`:**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: "health,info,prometheus"
  metrics:
    enable:
      jvm: true
    distribution:
      percentiles-histogram:
        rocksdb.read: true
```

**Custom metric registration:**
```java
// In ConnectionCountHandler @PostConstruct — registers connection gauge
Gauge.builder("mqtt.connections.active", connectionCount, AtomicInteger::get)
    .register(meterRegistry);

// In DefaultRocksDbStorage — wraps reads with latency timer
Timer.Sample sample = Timer.start(meterRegistry);
byte[] value = db.get(cfHandle, key.getBytes(StandardCharsets.UTF_8));
sample.stop(Timer.builder("rocksdb.read.latency")
    .tag("cf", cfName)
    .register(meterRegistry));
```

### Pattern 7: Multi-Stage Dockerfile (Multi-Arch)

**What:** Two-stage Docker build: Maven build stage on JDK image, runtime stage on JRE image. Multi-arch via `docker buildx`.

**Critical constraint:** Base image MUST be `eclipse-temurin:17-jre-jammy` (glibc). Alpine is hard-blocked.

**Dockerfile:**
```dockerfile
# Stage 1: Build
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app
# Copy POM first for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn -DskipTests package -q

# Stage 2: Runtime
# MUST use jammy (glibc) — NOT alpine (musl — incompatible with RocksDB JNI)
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# curl required for Docker healthcheck (D-14)
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/tbmq-lightweight.jar app.jar

# RocksDB data directory — mount a volume here for persistence (D-12)
VOLUME ["/data/rocksdb"]

EXPOSE 1883 8083

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -sf http://localhost:8083/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Dio.netty.leakDetection.level=DISABLED", \
    "-jar", "app.jar"]
```

**Multi-arch build command:**
```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --tag thingsboard/tbmq-lightweight:latest \
  --push \
  .
```

**Note on Maven in build stage:** The reference codebase does not include a Maven wrapper. The Dockerfile must either copy a `mvnw` wrapper (preferred for reproducibility) or use the system Maven from the base image. The `eclipse-temurin:17-jdk-jammy` image does not include Maven — use a multi-stage build with `maven:3.9-eclipse-temurin-17-jammy` for the build stage instead.

**Revised build stage recommendation:**
```dockerfile
FROM maven:3.9-eclipse-temurin-17-jammy AS build
```

### Anti-Patterns to Avoid

- **Never open RocksDB column families in separate `RocksDB.open()` calls.** Open all column families in a single call. Separate opens cause WAL management issues and are not supported correctly by the column family API.
- **Never store RocksDB data inside the container layer.** Always use `/data/rocksdb` which is declared as a Docker `VOLUME`. Container layer writes bypass OS page cache and are lost on `docker rm`.
- **Never call `RocksDB.loadLibrary()` lazily** (e.g., on first read). Call it in `SmartLifecycle.start()` unconditionally before any DB operation. Lazy loading in a Spring bean causes race conditions on startup.
- **Never close the RocksDB instance before closing all `ColumnFamilyHandle` objects.** Always close handles first, then the database. Reverse order causes JNI segfaults.
- **Never share the Tomcat/Actuator thread pool with Netty.** Spring Boot Actuator runs on embedded Tomcat (port 8083). Netty runs its own EventLoopGroups (port 1883). They must remain independent; a slow Prometheus scrape must never delay MQTT I/O.
- **Never use Alpine-based Docker images.** Any `eclipse-temurin:17-jre-alpine` base will fail at broker startup on ARM64 with `UnsatisfiedLinkError`. Leave a comment in the Dockerfile to prevent future changes.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Prometheus metrics endpoint | Custom `/metrics` servlet | Spring Boot Actuator + `micrometer-registry-prometheus` | JVM metrics, GC, thread pool stats auto-registered; format compliance guaranteed |
| In-process cache with eviction | `ConcurrentHashMap` with manual eviction | Caffeine (managed by Spring Boot) | W-TinyLFU provides near-optimal hit rates; size bounding prevents OOM at 10K+ connections; Spring `@Cacheable` integration |
| JNI native library lifecycle | Manual `System.loadLibrary()` + shutdown hook | `RocksDB.loadLibrary()` in `SmartLifecycle.start()` + Spring `@PreDestroy` ordering | Spring lifecycle guarantees ordered initialization and destruction; shutdown hook ordering is unreliable under OOM |
| Connection count gauge | Custom counter with manual Prometheus output | Micrometer `Gauge.builder()` + `MeterRegistry` | Automatic Prometheus scraping format; integrated with all other metrics |
| HTTP healthcheck endpoint | Custom Spring MVC health endpoint | Spring Boot Actuator `/actuator/health` | Provides health indicators for disk space, liveness, readiness out of the box |
| Docker multi-arch build | Separate Dockerfiles per platform | `docker buildx build --platform linux/amd64,linux/arm64` | Single build command; manifest list automatically served; QEMU emulation for CI |

**Key insight:** For Phase 1, all infrastructure concerns (metrics, caching, lifecycle, healthcheck) are solved by Spring Boot 3.5 out of the box. The Phase 1 code volume is small. The risk is in the JNI integration (RocksDB lifecycle ordering and Docker base image selection) — not in the application logic.

---

## Common Pitfalls

### Pitfall 1: RocksDB JNI Native Memory Leak (v7+ — No Finalizers)

**What goes wrong:** RocksDB v7+ removed Java finalizers. Any `RocksDB`, `ColumnFamilyHandle`, `Options`, `ReadOptions`, `WriteOptions`, or `Iterator` object that is not explicitly `close()`d causes silent native memory leaks. The JVM heap looks healthy; RSS grows steadily.

**Why it happens:** Java developers rely on GC to clean up objects. RocksDB JNI objects allocate native memory (outside the heap) that is only released by explicit `close()` calls.

**How to avoid:** In `SmartLifecycle.stop()`, close all objects in order: (1) iterators, (2) `ReadOptions`/`WriteOptions`, (3) `ColumnFamilyHandle` list, (4) `RocksDB` instance, (5) `DBOptions`. Use null checks before each close. Consider wrapping RocksDB operations in a single service class that manages all handle lifetimes.

**Warning signs:** Container memory limit reached while JVM heap is below 60%; RSS grows 50-100 MB per hour under normal load with no heap growth.

---

### Pitfall 2: RocksDB JNI Fails on ARM64 Alpine (Hard-Blocked)

**What goes wrong:** `rocksdbjni` fat JAR ships glibc binaries. Alpine uses musl libc. On ARM64 Alpine: `libstdc++.so.6: cannot open shared object file: No such file or directory`. Broker fails to start.

**Why it happens:** Alpine looks small and clean. The glibc/musl incompatibility is not documented prominently in RocksDB release notes.

**How to avoid:** Use `eclipse-temurin:17-jre-jammy` (Ubuntu Jammy, glibc). Add a comment to the Dockerfile: `# WARNING: DO NOT change to alpine — RocksDB JNI requires glibc (musl incompatible)`. This is a hard-block, not a workaround choice.

**Warning signs:** CI passes on x86_64 but ARM64 Docker build starts failing at broker startup; `UnsatisfiedLinkError` in container logs.

---

### Pitfall 3: Column Family Handle Close Order Causes JNI Segfault

**What goes wrong:** If `db.close()` is called before all `ColumnFamilyHandle` objects are closed, the JNI layer segfaults. The JVM crashes with no clean stack trace, violating OPS-06.

**Why it happens:** The RocksDB Java API does not enforce close order; the C++ layer crashes if the DB object is deallocated while handles still reference it.

**How to avoid:** Always close handles before the database. In `SmartLifecycle.stop()`:
```java
cfHandles.forEach(ColumnFamilyHandle::close);
db.close();
options.close();
```

**Warning signs:** Container exits with status 139 (SIGSEGV) during graceful shutdown.

---

### Pitfall 4: Spring Boot Graceful Shutdown and SmartLifecycle Phase Ordering

**What goes wrong:** `server.shutdown=graceful` in Spring Boot coordinates the shutdown of the embedded Tomcat server but does NOT automatically coordinate with `SmartLifecycle` phases in the expected order. If SmartLifecycle phases are not configured correctly, Tomcat may shut down before Netty drains its connections, or RocksDB may close before services that write to it complete their own shutdown.

**Why it happens:** Spring's graceful shutdown sends a signal to the embedded web server. SmartLifecycle beans stop in reverse phase order (highest phase first). Without explicit phase assignments, all SmartLifecycle beans run at phase 0 — unordered.

**How to avoid:**
- RocksDB `SmartLifecycle.getPhase()` = `Integer.MAX_VALUE` (stops last)
- Netty bootstrap `SmartLifecycle.getPhase()` = `0` (stops before RocksDB)
- Use `spring.lifecycle.timeout-per-shutdown-phase=10s` to bound each phase

**Warning signs:** `IllegalStateException: RocksDB has been closed` during shutdown; Netty `ChannelException` after RocksDB close.

---

### Pitfall 5: `docker buildx` Requires Builder with Multi-Platform Support

**What goes wrong:** Running `docker buildx build --platform linux/amd64,linux/arm64` on a fresh environment fails with `ERROR: multiple platforms feature is currently not supported for docker driver`.

**Why it happens:** The default Docker driver doesn't support multi-platform builds. A `docker-container` or `kubernetes` driver is required.

**How to avoid:**
```bash
# One-time setup (CI or developer machine)
docker buildx create --name multiarch --driver docker-container --use
docker buildx inspect --bootstrap
# Then build
docker buildx build --platform linux/amd64,linux/arm64 --push -t thingsboard/tbmq-lightweight:latest .
```

**Warning signs:** `ERROR: multiple platforms feature is currently not supported for docker driver` — fix by creating a new builder with `--driver docker-container`.

---

### Pitfall 6: Maven Build Stage in Dockerfile Has No Maven Installed

**What goes wrong:** The Dockerfile uses `eclipse-temurin:17-jdk-jammy` as the build stage, but this image does not include Maven. The `mvn package` command fails with `mvn: command not found`.

**Why it happens:** `eclipse-temurin` images provide only the JDK, not build tools.

**How to avoid:** Use `maven:3.9-eclipse-temurin-17-jammy` as the build stage image — it includes Maven 3.9.x with Eclipse Temurin 17 on Jammy base:
```dockerfile
FROM maven:3.9-eclipse-temurin-17-jammy AS build
```

**Warning signs:** `mvn: command not found` in Docker build output.

---

### Pitfall 7: RocksDB Data Directory Not Volume-Mounted

**What goes wrong:** Developer runs `docker run` without a volume mount. Credentials written to RocksDB appear to persist until `docker rm` is run, then vanish. OPS-04 appears to pass locally but fails in CI where containers are recreated.

**Why it happens:** Without `VOLUME ["/data/rocksdb"]` in the Dockerfile and a `-v` flag at runtime, RocksDB data goes into the container's writable layer. It survives `docker stop`/`docker start` but is lost on `docker rm`.

**How to avoid:** Declare `VOLUME ["/data/rocksdb"]` in the Dockerfile. Log a WARNING at startup if the path appears to be non-persistent (heuristic: check if `/data/rocksdb` is writable but not a mount point). Document the volume mount requirement prominently in the README.

**Warning signs:** Credentials created in one `docker run` session are gone after `docker run` again with the same image.

---

## Code Examples

Verified patterns from official sources and existing TBMQ codebase:

### RocksDB Multi-Column-Family Open
```java
// Source: RocksDB Java wiki — open all CFs in single call
// https://github.com/facebook/rocksdb/wiki/RocksJava-Basics#opening-a-database-with-column-families
RocksDB.loadLibrary(); // Must be called first

List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
    new ColumnFamilyDescriptor("credentials".getBytes(StandardCharsets.UTF_8)),
    new ColumnFamilyDescriptor("acl_rules".getBytes(StandardCharsets.UTF_8)),
    new ColumnFamilyDescriptor("retained_messages".getBytes(StandardCharsets.UTF_8)),
    new ColumnFamilyDescriptor("metadata".getBytes(StandardCharsets.UTF_8))
);
List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
DBOptions options = new DBOptions()
    .setCreateIfMissing(true)
    .setCreateMissingColumnFamilies(true);

try (DBOptions opts = options) {
    RocksDB db = RocksDB.open(opts, "/data/rocksdb", cfDescriptors, cfHandles);
}
// cfHandles[0] = DEFAULT_CF, cfHandles[1] = credentials, etc.
```

### RocksDB Shutdown Order
```java
// Source: PITFALLS.md — close handles before DB
@Override
public void stop() {
    log.info("Closing RocksDB...");
    // Step 1: close all column family handles
    for (ColumnFamilyHandle handle : cfHandles) {
        if (handle != null) {
            handle.close();
        }
    }
    // Step 2: close the database (flushes memtable)
    if (db != null) {
        db.close();
    }
    // Step 3: close options objects
    if (dbOptions != null) {
        dbOptions.close();
    }
    running = false;
    log.info("RocksDB closed");
}
```

### Caffeine Cache Configuration
```java
// Source: Spring Boot docs — CaffeineCacheManager with custom specs
// Caffeine 3.2.1 managed by Spring Boot 3.5.3
@Configuration
@EnableCaching
public class CacheConfiguration {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheSpecification("credentials=maximumSize=10000,expireAfterWrite=15m");
        manager.setCaffeine(Caffeine.newBuilder()
            .recordStats()); // enables CaffeineCacheMetrics for Micrometer
        return manager;
    }
}
```

### Application Entry Point (following TBMQ pattern)
```java
// Source: existing ThingsboardMqttBrokerApplication.java
@Slf4j
@SpringBootApplication
@EnableScheduling
public class TbmqLightweightApplication {

    private static final String SPRING_CONFIG_NAME_KEY = "--spring.config.name";
    private static final String DEFAULT_SPRING_CONFIG_PARAM =
        SPRING_CONFIG_NAME_KEY + "=tbmq-lightweight";

    public static void main(String[] args) {
        try {
            SpringApplication.run(TbmqLightweightApplication.class,
                updateArguments(args));
        } catch (Exception e) {
            log.error("Failed to start application.", e);
            System.exit(1);
        }
    }

    private static String[] updateArguments(String[] args) {
        if (Arrays.stream(args).noneMatch(arg -> arg.startsWith(SPRING_CONFIG_NAME_KEY))) {
            String[] modified = Arrays.copyOf(args, args.length + 1);
            modified[args.length] = DEFAULT_SPRING_CONFIG_PARAM;
            return modified;
        }
        return args;
    }
}
```

### Write Schema Version at First Start
```java
// Source: Architecture decisions D-04, D-07 — schema version in metadata CF
private static final String SCHEMA_VERSION_KEY = "schema_version";
private static final String CURRENT_SCHEMA_VERSION = "1";

private void initSchemaVersion() throws RocksDBException {
    ColumnFamilyHandle metadataCf = cfHandlesByName.get("metadata");
    byte[] existing = db.get(metadataCf,
        SCHEMA_VERSION_KEY.getBytes(StandardCharsets.UTF_8));
    if (existing == null) {
        db.put(metadataCf,
            SCHEMA_VERSION_KEY.getBytes(StandardCharsets.UTF_8),
            CURRENT_SCHEMA_VERSION.getBytes(StandardCharsets.UTF_8));
        log.info("Initialized RocksDB schema version: {}", CURRENT_SCHEMA_VERSION);
    } else {
        log.info("RocksDB schema version: {}", new String(existing, StandardCharsets.UTF_8));
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Netty `NioEventLoopGroup` on Linux | `EpollEventLoopGroup` with NIO fallback | Netty 4.0+ | Epoll uses edge-triggered I/O; eliminates JDK NIO busy-wait; required for 10K+ connections at low CPU |
| Guava Cache | Caffeine (Spring Boot default) | Spring 5 / Spring Boot 2.1 | W-TinyLFU eviction; 2-5x throughput over Guava under concurrency |
| Separate Docker builds per arch | `docker buildx --platform` | Docker 19.03 | Single command produces multi-arch manifest; no separate CI jobs per platform |
| Docker Alpine base for small images | Debian-based JRE with jlink | RocksDB JNI requirement | Alpine (musl) is incompatible with RocksDB glibc binaries; Debian is required |
| Spring Boot `@PreDestroy` ordering | `SmartLifecycle` with phase ordering | Spring 3+ | `@PreDestroy` ordering is undefined; `SmartLifecycle` provides deterministic phase-based ordering |

**Deprecated/outdated:**
- RocksDB Java finalizers: Removed in v7+. Any code from pre-2021 tutorials that relies on GC to clean up RocksDB objects is incorrect and will leak native memory.
- Netty 4.2 `NioEventLoopGroup`: Deprecated in Netty 4.2, replaced by `MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())`. Do not migrate to 4.2 in Phase 1.
- `eclipse-temurin:17-jdk-jammy` as runtime base: JDK includes compiler tools (~200MB extra). Use `eclipse-temurin:17-jre-jammy` for runtime (~190MB total).

---

## Open Questions

1. **Maven wrapper (`mvnw`) in the new repository**
   - What we know: The existing TBMQ repo does not use a Maven wrapper. The Docker build stage needs Maven.
   - What's unclear: Whether to add `mvnw` + `.mvn/wrapper/` to the new repo or rely on the `maven:3.9-eclipse-temurin-17-jammy` Docker image for builds.
   - Recommendation: Use the Maven wrapper (`mvnw`) generated by `mvn -N io.takari:maven:wrapper` — it pins the Maven version and makes local builds reproducible without requiring system Maven.

2. **Spring Boot config name convention**
   - What we know: Existing TBMQ uses `--spring.config.name=thingsboard-mqtt-broker`. D-13 establishes `TBMQ_` prefix. The app entry point must set a config name.
   - What's unclear: Whether to use `tbmq-lightweight` (matches project name) or `tbmq-broker` (shorter).
   - Recommendation: Use `tbmq-lightweight` as the Spring config name to match the Docker image tag and project identity.

3. **Netty `netty-all` vs. individual Netty artifacts**
   - What we know: The reference TBMQ uses `netty-all` (fat artifact). The STACK.md recommends `netty-all`.
   - What's unclear: In Phase 1 (no MQTT codec yet), `netty-all` pulls in unused codec dependencies.
   - Recommendation: Start with `netty-all` to match TBMQ conventions. Dependency trim is a post-R1 optimization.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 17 (JDK) | Maven build, application runtime | ✓ | OpenJDK 17.0.18 | — |
| Maven | Build system | ✓ | 3.9.2 | — |
| Docker | Container build and run | ✓ | 28.1.1 | — |
| Docker Buildx | Multi-arch image builds (OPS-03) | ✓ | 0.23.0 | — |
| curl | Docker healthcheck (D-14) | ✓ | 7.81.0 | — |
| Node.js | Not required in Phase 1 (no UI) | ✓ | v24.14.0 | Not needed |

**Missing dependencies with no fallback:** None — all required tools are present.

**Missing dependencies with fallback:** None identified.

**Note on QEMU for ARM64 builds:** `docker buildx` with `--platform linux/arm64` on an x86 host requires QEMU emulation OR a native ARM64 build node. QEMU support can be enabled with `docker run --privileged --rm tonistiigi/binfmt --install all`. This is standard in CI environments but may need setup on a fresh developer machine. The builder must be a `docker-container` driver, not the default `docker` driver.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (managed by Spring Boot 3.5.3 via `spring-boot-starter-test`) |
| Config file | None in new repo — added in Wave 0 via `pom.xml` Surefire configuration |
| Quick run command | `mvn test -Dtest="*Test" -q` |
| Full suite command | `mvn verify -q` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| INFR-02 | RocksDB opens with 4 CFs; write/read/delete per CF; schema version written on first start; data persists across `start()`/`stop()`/`start()` cycle | Unit (in-process, real RocksDB against temp dir) | `mvn test -Dtest="RocksDbStorageTest" -q` | ❌ Wave 0 |
| INFR-03 | Caffeine cache is configured; `@Cacheable` method is invoked once on double call; eviction by size works | Unit (Spring context) | `mvn test -Dtest="CacheConfigurationTest" -q` | ❌ Wave 0 |
| OPS-02 | Spring Boot application context starts without errors; port 1883 accepts TCP connection; port 8083 returns 200 on `/actuator/health` | Integration (Spring Boot Test, `@SpringBootTest`) | `mvn test -Dtest="ApplicationStartupTest" -q` | ❌ Wave 0 |
| OPS-03 | Docker image builds for `linux/amd64` and `linux/arm64` without errors | Manual / CI (docker buildx) | `docker buildx build --platform linux/amd64,linux/arm64 .` | ❌ Wave 0 (Dockerfile) |
| OPS-04 | RocksDB data survives `stop()`/`start()` cycle on same directory | Unit (uses INFR-02 test above) | Covered by `RocksDbStorageTest` | ❌ Wave 0 |
| OPS-05 | Application starts within 5 seconds (measured from `main()` to first TCP connection accepted) | Smoke (embedded start timer in `ApplicationStartupTest`) | `mvn test -Dtest="ApplicationStartupTest" -q` | ❌ Wave 0 |
| OPS-06 | Graceful shutdown: SIGTERM causes `SmartLifecycle.stop()` to close Netty, flush RocksDB, no `UnsatisfiedLinkError` or exit code ≠ 0 | Integration (`@SpringBootTest` with `ConfigurableApplicationContext.close()`) | `mvn test -Dtest="GracefulShutdownTest" -q` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `mvn test -Dtest="RocksDbStorageTest,CacheConfigurationTest" -q`
- **Per wave merge:** `mvn verify -q`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `src/test/java/org/thingsboard/mqtt/broker/lightweight/storage/RocksDbStorageTest.java` — covers INFR-02, OPS-04
- [ ] `src/test/java/org/thingsboard/mqtt/broker/lightweight/cache/CacheConfigurationTest.java` — covers INFR-03
- [ ] `src/test/java/org/thingsboard/mqtt/broker/lightweight/ApplicationStartupTest.java` — covers OPS-02, OPS-05
- [ ] `src/test/java/org/thingsboard/mqtt/broker/lightweight/GracefulShutdownTest.java` — covers OPS-06
- [ ] `pom.xml` with JUnit 5 via `spring-boot-starter-test` + Surefire plugin
- [ ] Framework: `spring-boot-starter-test` includes JUnit 5, Mockito, AssertJ — no additional install needed

---

## Project Constraints (from CLAUDE.md)

Actionable directives from `./CLAUDE.md` relevant to Phase 1. Planner must verify all generated code complies.

| Directive | Category | Application to Phase 1 |
|-----------|----------|------------------------|
| Java 17 minimum; configured via `maven.compiler.source`/`target` | Language | `pom.xml` must set `<java.version>17</java.version>` (Spring Boot parent uses this property) |
| Docker base image: glibc required | Runtime | Enforced by D-11: `eclipse-temurin:17-jre-jammy`. Alpine is hard-blocked. |
| Spring Boot 3.x (reference codebase uses 3.4.13) | Framework | New repo uses 3.5.3. Acceptable — separate codebase. |
| Netty 4.1.x (reference codebase uses 4.1.128; 4.2 has breaking changes) | Framework | Pin `netty.version=4.1.122.Final` in pom.xml properties. |
| Interface + `Default` prefix for implementations in application code | Naming | `RocksDbStorage` (interface), `DefaultRocksDbStorage` (implementation) |
| `@Slf4j` on all service classes | Logging | All `@Service`, `@Component`, `@Configuration` classes get `@Slf4j` |
| `@RequiredArgsConstructor` + `private final` fields for constructor injection | DI | No `@Autowired` field injection in new code |
| `@ConfigurationProperties` with `@Data` for config beans | Config | `NettyConfiguration`, `StorageConfiguration` use this pattern |
| `UPPER_SNAKE_CASE` for constants | Naming | `SCHEMA_VERSION_KEY`, `DEFAULT_COLUMN_FAMILY_NAME` etc. |
| Use GSD workflow before making file changes | Process | This research feeds the planner; no direct file changes outside GSD workflow |
| Conventional Commits format for all commit messages | Git | Plan tasks must specify commit messages using `feat:`, `chore:`, `refactor:` etc. |

---

## Sources

### Primary (HIGH confidence)
- Maven Central API — Spring Boot 3.5.3, RocksDB 9.7.4, Netty 4.1.122.Final, Caffeine 3.2.1, Micrometer 1.15.1 — versions verified 2026-04-03
- Spring Boot 3.5.3 BOM (`spring-boot-dependencies-3.5.3.pom`) — Netty 4.1.122.Final, Caffeine 3.2.1, Micrometer 1.15.1 confirmed
- Existing TBMQ codebase (`application/src/main/java/.../server/AbstractMqttServerBootstrap.java`) — Netty bootstrap pattern
- Existing TBMQ codebase (`application/src/main/java/...ThingsboardMqttBrokerApplication.java`) — application entry point pattern
- `.planning/research/STACK.md` — technology choices with rationale (researched 2026-04-02)
- `.planning/research/ARCHITECTURE.md` — component boundaries, patterns, data flow (researched 2026-04-02)
- `.planning/research/PITFALLS.md` — RocksDB JNI pitfalls, shutdown ordering (researched 2026-04-02)
- `.planning/phases/01-foundation/01-CONTEXT.md` — locked decisions D-01 through D-17

### Secondary (MEDIUM confidence)
- RocksDB Java wiki: `https://github.com/facebook/rocksdb/wiki/RocksJava-Basics` — multi-CF open pattern, lifecycle
- Docker multi-platform build docs: `https://docs.docker.com/build/building/multi-platform/` — buildx pattern

### Tertiary (LOW confidence)
- None — all critical claims are verified via Maven Central or official docs.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all versions verified against Maven Central 2026-04-03; Spring Boot BOM versions confirmed
- Architecture: HIGH — based on existing TBMQ codebase analysis + locked decisions from CONTEXT.md
- Pitfalls: HIGH — RocksDB JNI pitfalls verified via official GitHub issues documented in PITFALLS.md; Docker constraint verified via STACK.md

**Research date:** 2026-04-03
**Valid until:** 2026-07-03 (90 days — stable stack, no fast-moving components in Phase 1)
