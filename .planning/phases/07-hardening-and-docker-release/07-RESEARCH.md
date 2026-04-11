# Phase 7: Hardening and Docker Release - Research

**Researched:** 2026-04-11
**Domain:** Micrometer metrics, JUnit soak testing, Spring startup events, /proc/mounts volume detection, shell scripting for ARM64 validation
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Add only metrics required by success criteria SC-1: message rates in/out (counters), auth success/failure counts (counters), and dispatch queue depth (gauge). No additional operational metrics in R1.
- **D-02:** Dispatch queue depth is a simple Gauge (current queue size snapshot), matching the pattern used by `ConnectionCountHandler`. No histogram or high-water-mark tracking.
- **D-03:** Auth success/failure counters are incremented in the auth service layer (not the actor), so all transport types (TCP, TLS, WS, WSS) are covered automatically.
- **D-04:** Verify `mqtt.messages.received.total` is actually incremented in all PUBLISH paths. Add `mqtt.messages.delivered.total` for outbound message rate.
- **D-05:** Soak test is a custom JUnit-based long-running integration test using Paho/HiveMQ client libraries already in the project. No external tooling (JMeter, emqtt-bench) required.
- **D-06:** Load profile: 500 concurrent clients, 1,000 msg/sec sustained, mix of QoS 0/1/2. Duration configurable — default 1 hour for CI, 24 hours for manual full soak.
- **D-07:** Netty ByteBuf leak detection set to `PARANOID` (`-Dio.netty.leakDetection.level=PARANOID`) during soak tests. Assert zero leak warnings in log output.
- **D-08:** RSS stability validated via JVM heap metrics from Micrometer (`jvm.memory.used`). Sample at intervals during the test, assert final heap within 20% of steady-state baseline.
- **D-09:** Startup warnings use `log.warn()` with a prominent multi-line banner block (bordered with `===`). Visible in `docker logs` output.
- **D-10:** Warnings triggered on `ApplicationReadyEvent` — runs after all SmartLifecycle components have started.
- **D-11:** Three warning conditions: (1) TLS not configured, (2) `/data/rocksdb` not volume-mounted (overlay filesystem check), (3) retained messages are in-memory only (always true in R1).
- **D-12:** Volume-mount detection: read `/proc/mounts`, check if RocksDB data path filesystem type is `overlay`.
- **D-13:** ARM64 validation is a manual test script (`scripts/arm64-validate.sh`).
- **D-14:** Script scope: container starts, MQTT client connects over TCP, publishes and receives a message, RocksDB data survives container restart.
- **D-15:** Script runnable on any ARM64 device: Raspberry Pi, Mac M-series, AWS Graviton.

### Claude's Discretion

- Exact metric names and tag labels (follow Micrometer/Prometheus conventions)
- Soak test JUnit class structure and assertion mechanics
- Startup warning banner format and exact wording
- ARM64 validation script implementation details (mosquitto_pub/sub vs. Paho CLI)
- Whether to add a `docker-compose.soak.yml` for convenient soak test execution
- Soak test steady-state baseline sampling strategy (warmup period, sample interval)

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| OPS-01 | Broker exposes Prometheus-compatible `/metrics` endpoint with connection counts, message rates, and error counters | Metrics endpoint already enabled in yml; research identifies 4 missing increments and the Gauge pattern for queue depth |
</phase_requirements>

---

## Summary

Phase 7 completes the Prometheus metrics surface, validates correctness under load, and prepares a release-ready Docker image. The codebase audit reveals that `mqtt.messages.received.total` is **registered** in `BrokerMetricsService` but is **never incremented anywhere** — the counter is always zero at runtime. This is the primary gap. Auth ACL-denied counters exist in `ClientActor` but connection-level auth success/failure (per D-03) is missing from `DefaultLightweightAuthService`. The dispatch queue depth Gauge and the `mqtt.messages.delivered.total` counter are also absent.

The soak test builds directly on `AbstractMqttIntegrationTest` and the existing Paho/HiveMQ test clients. The main soak pattern is a JUnit `@Tag("soak")` test excluded from the standard Surefire run, activated via a profile. The startup warning service follows `DefaultCredentialsInstaller` exactly — an `@EventListener(ApplicationReadyEvent.class)` bean at `@Order(2)` so it runs after credentials are installed. Volume-mount detection via `/proc/mounts` is straightforward string parsing; the format (one entry per line: `device mountpoint fstype options dump pass`) is stable across Linux kernels.

The `scripts/arm64-validate.sh` script uses `mosquitto_pub`/`mosquitto_sub` (available on any ARM64 device via package manager) rather than a JVM client, keeping the script dependency-free. The Dockerfile already has the correct base image, healthcheck, and VOLUME declaration; the only Docker change is an optional `--build-arg` for enabling PARANOID leak detection at image build time for soak scenarios.

**Primary recommendation:** Four targeted code changes (metrics increments + queue gauge), one new service (StartupWarningService), one new test class (SoakTest), one shell script. All patterns are directly reusable from existing code.

---

## Standard Stack

### Core (already in project)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Micrometer (`micrometer-registry-prometheus`) | managed by Spring Boot 3.5.3 | Counter/Gauge/Timer registration | Integrated with Spring Boot Actuator; `/actuator/prometheus` already enabled [VERIFIED: codebase] |
| Spring Boot Actuator | managed by Spring Boot 3.5.3 | `/actuator/prometheus` endpoint | Already configured in `tbmq-lightweight.yml` [VERIFIED: codebase] |
| Eclipse Paho v3 | 1.2.5 | Soak test MQTT clients | Already in test scope in `pom.xml` [VERIFIED: codebase] |
| Eclipse Paho v5 | 1.2.5 | Soak test MQTT 5.0 clients | Already in test scope in `pom.xml` [VERIFIED: codebase] |
| Awaitility | managed by Spring Boot 3.5.3 | Async assertions in soak test | Already in test scope [VERIFIED: codebase] |
| JUnit 5 | managed by Spring Boot 3.5.3 | Soak test execution | Already in test scope [VERIFIED: codebase] |

### No New Dependencies Required

All libraries needed for Phase 7 are already present. No `pom.xml` changes needed except for Maven Surefire `excludedGroups` configuration for soak tests.

---

## Architecture Patterns

### Pattern 1: Metrics Counter Increment (already used in `ClientActor`)

`ClientActor` already uses the dynamic counter pattern for ACL denials:

```java
// Source: lightweight/src/main/java/.../actors/client/ClientActor.java (verified)
meterRegistry.counter("mqtt.auth.denied.total", "type", "subscribe").increment();
meterRegistry.counter("mqtt.auth.denied.total", "type", "publish").increment();
```

`BrokerMetricsService` registers counters at startup (Micrometer deduplicates by name+tags). The actor then calls `meterRegistry.counter(name, tags...).increment()` on the hot path. This works because Micrometer caches the meter lookup.

**Optimization for hot path:** For very frequent operations, cache the `Counter` reference instead of calling `meterRegistry.counter()` on every message:

```java
// Source: ConnectionCountHandler pattern (verified: codebase)
private Counter receivedCounter;

@PostConstruct
void init() {
    receivedCounter = Counter.builder("mqtt.messages.received.total")
            .description("Total MQTT messages received from publishers")
            .register(meterRegistry);
}

// In processPublish():
receivedCounter.increment();
```

### Pattern 2: Gauge for Queue Depth (existing pattern)

`ConnectionCountHandler` shows the exact pattern — `AtomicInteger` backed Gauge:

```java
// Source: ConnectionCountHandler.java (verified: codebase)
Gauge.builder("mqtt.connections.active", connectionCount, AtomicInteger::get)
        .description("Current number of active MQTT connections")
        .register(meterRegistry);
```

For dispatch queue depth, the `BlockingQueue` already has a `size()` method. Register at `start()` after the queue is created:

```java
// In DefaultMsgDispatcherService.start() — after queue = queueFactory.createQueue()
Gauge.builder("mqtt.dispatch.queue.depth", queue, Queue::size)
        .description("Current number of messages waiting in dispatch queue")
        .register(meterRegistry);
```

**CRITICAL:** The Gauge lambda must reference the queue instance, not a local variable. Since `queue` is a field, capture `this.queue` or use a lambda that reads the field. The lambda is called each time Prometheus scrapes.

### Pattern 3: Auth Counters in Auth Service Layer (D-03)

Per D-03, counters must be in `DefaultLightweightAuthService.authenticate()` so all transport types (TCP, TLS, WS, WSS) are covered. The auth service is called from `ClientActor.processConnect()` which is transport-agnostic.

```java
// DefaultLightweightAuthService — add fields and @PostConstruct
private Counter authSuccessCounter;
private Counter authFailureCounter;

@PostConstruct
void initMetrics() {
    authSuccessCounter = Counter.builder("mqtt.auth.success.total")
            .description("Total successful MQTT authentication attempts")
            .register(meterRegistry);
    authFailureCounter = Counter.builder("mqtt.auth.failure.total")
            .description("Total failed MQTT authentication attempts")
            .register(meterRegistry);
}

// In authenticate(), after each success path:
authSuccessCounter.increment();
// After each failure path:
authFailureCounter.increment();
```

**Note:** `DefaultLightweightAuthService` already has `@RequiredArgsConstructor` — add `MeterRegistry` as a new `final` field for constructor injection.

### Pattern 4: Messages Delivered Counter (in ClientActor.processDeliver)

`processDeliver()` handles all outbound deliveries. Add increment at the point where `writeAndFlush` is called for QoS 0, 1, and 2:

```java
// Source: ClientActor.processDeliver() — verified: codebase
// Add counter field and @PostConstruct not applicable in actor (not a Spring bean)
// Use meterRegistry.counter() pattern matching existing ClientActor usage:
meterRegistry.counter("mqtt.messages.delivered.total").increment();
```

`ClientActor` already has `MeterRegistry meterRegistry` as a constructor field [VERIFIED: codebase]. No new injection needed.

### Pattern 5: Startup Warning Service

`DefaultCredentialsInstaller` is the exact reference pattern [VERIFIED: codebase]:
- `@Service` + `@RequiredArgsConstructor` + `@Slf4j`
- `@EventListener(ApplicationReadyEvent.class)` + `@Order(2)` (runs after `@Order(1)` credentials installer)
- Inject config beans: `TlsConfiguration`, `StorageConfiguration`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class StartupWarningService {

    private final TlsConfiguration tlsConfig;
    private final StorageConfiguration storageConfig;

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void checkStartupWarnings() {
        List<String> warnings = new ArrayList<>();

        if (!tlsConfig.isEnabled()) {
            warnings.add("TLS is not configured. MQTT traffic is unencrypted.");
            warnings.add("  Set TBMQ_TLS_ENABLED=true and mount certs to enable MQTTS on port 8883.");
        }

        if (isRocksDbPathOverlay(storageConfig.getPath())) {
            warnings.add("RocksDB path '" + storageConfig.getPath() + "' is NOT volume-mounted.");
            warnings.add("  Credentials and ACLs will be LOST when the container is removed.");
            warnings.add("  Mount a volume: docker run -v /host/data:/data/rocksdb ...");
        }

        warnings.add("Retained messages are stored in-memory only.");
        warnings.add("  They will be lost on broker restart (R1 limitation).");

        if (!warnings.isEmpty()) {
            String border = "=".repeat(70);
            log.warn("\n{}\n  TBMQ LIGHTWEIGHT — STARTUP WARNINGS\n{}\n  {}\n{}",
                    border, border,
                    String.join("\n  ", warnings),
                    border);
        }
    }
}
```

### Pattern 6: /proc/mounts Volume Detection (D-12)

`/proc/mounts` format on Linux (verified on this machine): each line is `device mountpoint fstype options dump pass`. For Docker containers with no volume mount, `/data/rocksdb` is covered by the root overlay filesystem entry. Parse by finding the closest parent mount point and checking its fstype.

**Algorithm:**
1. Read `/proc/mounts` as a list of lines
2. Parse each line into: `{device, mountpoint, fstype, options}`
3. Find the entry whose `mountpoint` is the longest prefix of `storageConfig.getPath()`
4. If that entry's `fstype` is `overlay` → path is inside Docker's writable layer → warn
5. If `fstype` is `ext4`, `xfs`, `btrfs`, etc. → a real volume is mounted → no warning
6. If `/proc/mounts` cannot be read (non-Linux) → skip check silently

```java
// Source: [ASSUMED] — standard Java file reading, /proc/mounts format is POSIX-stable
private boolean isRocksDbPathOverlay(String dataPath) {
    try {
        Path mountsFile = Path.of("/proc/mounts");
        if (!Files.exists(mountsFile)) {
            return false; // not Linux, skip
        }
        String bestMountPoint = "/";
        String bestFsType = "unknown";
        for (String line : Files.readAllLines(mountsFile)) {
            String[] parts = line.split("\\s+");
            if (parts.length < 3) continue;
            String mountPoint = parts[1];
            String fsType = parts[2];
            if (dataPath.startsWith(mountPoint) && mountPoint.length() > bestMountPoint.length()) {
                bestMountPoint = mountPoint;
                bestFsType = fsType;
            }
        }
        return "overlay".equals(bestFsType);
    } catch (IOException e) {
        log.debug("Could not read /proc/mounts for volume detection: {}", e.getMessage());
        return false;
    }
}
```

**Key edge case:** If the user mounts `/data` (parent) instead of `/data/rocksdb` (exact path), the algorithm correctly detects the volume because `/data` is the longest prefix that is a real mount.

### Pattern 7: Soak Test Structure

Extend `AbstractMqttIntegrationTest` — it already handles broker port allocation, credentials setup, and client cleanup [VERIFIED: codebase].

```java
@Tag("soak")
@SpringBootTest(properties = {
        "spring.config.name=tbmq-lightweight",
        "tbmq.netty.port=0",
        "tbmq.netty.max-connections=1000",
        "tbmq.storage.rocksdb.path=${java.io.tmpdir}/tbmq-soak-test",
        "tbmq.ws.port=0",
        "tbmq.wss.enabled=false",
        "management.server.port=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class SoakTest {

    // Duration: read from system property "soak.duration.minutes", default 60 (1 hour CI)
    // 500 clients: 250 publishers, 250 subscribers
    // Assertion: no "LEAK:" in captured log output during test run
    // Assertion: final heap < 1.2x steady-state heap after warmup
}
```

**Log capture for leak detection:** Use a custom Logback appender or scan the log file. The simplest approach for JUnit is to set `io.netty.leakDetection.level=PARANOID` in the Surefire argLine (already done in `pom.xml` — line 148 [VERIFIED: codebase]) and then check that no line containing `"LEAK:"` appears.

Since the soak test is excluded from the standard build with `@Tag("soak")`, add to `pom.xml` Surefire config:

```xml
<excludedGroups>soak</excludedGroups>
```

Run manually with:

```bash
mvn test -Dgroups=soak -Dsoak.duration.minutes=1440 -Dio.netty.leakDetection.level=PARANOID
```

### Pattern 8: Heap Stability Assertion (D-08)

Use Spring `ApplicationContext` to get the `MeterRegistry` bean, then call `meterRegistry.get("jvm.memory.used").gauge().value()` at intervals.

```java
// Warmup: run for 5 minutes, sample heap 10 times, compute baseline average
// Stable phase: run remaining duration, sample every 2 minutes
// Assert: last sample < baseline * 1.20 (within 20% of baseline per D-08)
private double getHeapUsedBytes() {
    return meterRegistry.get("jvm.memory.used")
            .tag("area", "heap")
            .gauge()
            .value();
}
```

**Note:** `jvm.memory.used` with tag `area=heap` is the sum of all heap pool gauges registered by Micrometer's JVM metrics [ASSUMED — standard Micrometer behavior, not verified in this session against current source].

### Pattern 9: ARM64 Validation Script

Use `mosquitto_pub`/`mosquitto_sub` — available via `apt install mosquitto-clients` on Raspberry Pi OS (Debian), Homebrew on macOS M-series, and most Linux ARM64 distributions. No JVM required on the test device.

```bash
#!/usr/bin/env bash
# scripts/arm64-validate.sh — TBMQ Lightweight ARM64 smoke test
# Prerequisites: Docker, mosquitto-clients
# Platforms: Raspberry Pi (armhf/arm64), Mac M-series (Docker Desktop), AWS Graviton

set -euo pipefail

IMAGE="${1:-thingsboard/tbmq-lightweight:latest}"
DATA_DIR=$(mktemp -d /tmp/tbmq-arm64-XXXXXX)

echo "[1/5] Starting broker..."
CONTAINER=$(docker run -d \
    -p 1883:1883 \
    -v "${DATA_DIR}:/data/rocksdb" \
    "$IMAGE")

trap 'docker stop "$CONTAINER" >/dev/null; docker rm "$CONTAINER" >/dev/null; rm -rf "$DATA_DIR"' EXIT

sleep 5  # wait for startup

echo "[2/5] Subscribing to test/smoke..."
RECV_FILE=$(mktemp)
mosquitto_sub -h localhost -p 1883 -u tbmq -P tbmq -t test/smoke -C 1 > "$RECV_FILE" &
SUB_PID=$!

sleep 1

echo "[3/5] Publishing test message..."
mosquitto_pub -h localhost -p 1883 -u tbmq -P tbmq -t test/smoke -m "arm64-ok"

wait "$SUB_PID"
RECEIVED=$(cat "$RECV_FILE")
if [ "$RECEIVED" != "arm64-ok" ]; then
    echo "FAIL: expected 'arm64-ok', got '$RECEIVED'"
    exit 1
fi
echo "  Message received: OK"

echo "[4/5] Verifying RocksDB persistence (restart test)..."
docker stop "$CONTAINER" >/dev/null
docker rm "$CONTAINER" >/dev/null

CONTAINER=$(docker run -d \
    -p 1883:1883 \
    -v "${DATA_DIR}:/data/rocksdb" \
    "$IMAGE")

sleep 5

echo "[5/5] Verifying default credentials survived restart..."
mosquitto_pub -h localhost -p 1883 -u tbmq -P tbmq -t test/restart -m "ok"
echo "  Credentials persisted: OK"

echo ""
echo "ARM64 validation PASSED"
```

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Prometheus metric exposition | Custom HTTP endpoint | `micrometer-registry-prometheus` + Spring Actuator | Already integrated; `/actuator/prometheus` enabled in yml [VERIFIED] |
| ByteBuf leak detection | Custom reference tracking | Netty PARANOID detection (`-Dio.netty.leakDetection.level=PARANOID`) | Netty's built-in sampler wraps every buffer allocation [ASSUMED] |
| JVM heap tracking | `/proc/self/status` RSS parsing | Micrometer `jvm.memory.used` gauge | JVM provides per-pool data; RSS includes JVM overhead |
| Test MQTT load generation | Custom socket writers | Paho `MqttClient` (already in test scope) | Multi-threaded, connection pooling, QoS state machine |
| Container filesystem type detection | Docker socket API calls | `/proc/mounts` file parsing | Simpler, no Docker SDK dependency, works inside container |

---

## Gap Analysis: Missing Metric Increments

This is the most important finding from the codebase audit.

### GAP 1: `mqtt.messages.received.total` — NEVER incremented [VERIFIED: codebase]

The counter is registered in `BrokerMetricsService.init()` but no code calls `.increment()` on it anywhere. It reports zero at runtime.

**Fix location:** `ClientActor.processPublish()` — after the ACL check passes and before `msgDispatcherService.dispatch()`. Also at the QoS 2 completion point in `processPubRel()` — because QoS 2 messages are only fully received when PUBREL arrives.

**Two increment points in `ClientActor`:**
1. `processPublish()` for QoS 0 and QoS 1 (immediately after ACL passes)
2. `processPubRel()` for QoS 2 (when message is actually delivered, post-PUBREL)

### GAP 2: `mqtt.messages.delivered.total` — not registered, not incremented [VERIFIED: codebase]

`BrokerMetricsService` does not register this counter. `ClientActor.processDeliver()` does not increment it.

**Fix location:**
1. Register in `BrokerMetricsService.init()` (for startup visibility at zero)
2. Increment in `ClientActor.processDeliver()` — add `meterRegistry.counter("mqtt.messages.delivered.total").increment()` at each `writeAndFlush()` call (QoS 0, QoS 1, QoS 2 paths)

### GAP 3: `mqtt.auth.success.total` and `mqtt.auth.failure.total` — not registered, not incremented [VERIFIED: codebase]

`DefaultLightweightAuthService.authenticate()` returns `AuthResult.success()` or `AuthResult.failure()` but calls no metrics. These must be added per D-03.

**`DefaultLightweightAuthService` requires `MeterRegistry` injection** — add as `final` field, constructor injection via `@RequiredArgsConstructor` pattern already in use.

### GAP 4: `mqtt.dispatch.queue.depth` Gauge — not registered [VERIFIED: codebase]

`DefaultMsgDispatcherService` only has `droppedMsgsCounter`. The queue depth Gauge must be registered in `start()` after `queue = queueFactory.createQueue()`.

**Pitfall:** Micrometer Gauge with `Queue::size` — the Gauge holds a weak reference to the `queue` object. Since `queue` is a field on a Spring bean (which is strongly referenced), the weak reference is safe from GC. This is the established pattern.

---

## Common Pitfalls

### Pitfall 1: Gauge Lambda Captures a Local Variable

**What goes wrong:** `Gauge.builder("...", localVar, x -> x.size())` — if `localVar` is a local variable, Micrometer's weak reference allows the object to be GC'd, and the Gauge starts returning `NaN`.

**Why it happens:** Micrometer Gauges hold weak references to prevent memory leaks. If the referenced object has no other strong references, it gets collected.

**How to avoid:** Always register Gauges against fields of long-lived beans (`this.queue`, `this.connectionCount`). In `DefaultMsgDispatcherService`, `queue` is an instance field — safe. [ASSUMED — standard Micrometer Gauge documentation warning]

### Pitfall 2: Counter Tag Mismatch (Prometheus Cardinality)

**What goes wrong:** `meterRegistry.counter("mqtt.auth.denied.total", "type", "subscribe")` in one place and `meterRegistry.counter("mqtt.auth.denied.total")` (no tags) in another — Micrometer treats these as different meters. Prometheus reports orphaned `{type=""}` series.

**How to avoid:** Always use the same tag set for a given metric name. If a counter can have no type, use a sentinel value like `"none"`. The existing `ClientActor` uses `"type"` tag consistently — follow the same pattern for auth counters.

### Pitfall 3: Soak Test Ports Conflict with Other Tests

**What goes wrong:** The soak test uses `@SpringBootTest` with `tbmq.netty.port=0` (random port), but with `@DirtiesContext`, if run in the same Surefire fork as other integration tests, Spring context recreation can race with RocksDB shutdown/startup.

**How to avoid:** Use a dedicated soak test RocksDB path (`/tmp/tbmq-soak-XXXXXX` via `@TempDir`). The soak test is tagged `@Tag("soak")` and excluded from normal runs. Run in isolation via `mvn test -Dgroups=soak`.

### Pitfall 4: Paho Client Thread Pool Exhaustion at 500 Clients

**What goes wrong:** Creating 500 `MqttClient` instances in a loop with the default Paho thread-per-client model can exhaust system thread limits or cause `OutOfMemoryError`.

**How to avoid:** Create clients in batches with `Executors.newFixedThreadPool(50)` for connection setup. Once connected, clients are event-driven (no thread-per-client for idle connections in Paho v3). [ASSUMED — based on Paho v3 architecture knowledge]

### Pitfall 5: ByteBuf Leak Detection Overhead in Production

**What goes wrong:** Leaving `PARANOID` in the production Dockerfile causes severe throughput degradation — every buffer allocation/release is tracked with a stack trace.

**How to avoid:** The Dockerfile already uses `DISABLED` for production. Soak tests override via JVM arg (`-Dio.netty.leakDetection.level=PARANOID` already set in Surefire `argLine` in `pom.xml` line 148 [VERIFIED: codebase]). Never commit `PARANOID` to the Dockerfile.

### Pitfall 6: /proc/mounts Parsing on Non-Linux or Inside Containers

**What goes wrong:** `/proc/mounts` may have unexpected entries inside Docker containers, especially with Docker Desktop on macOS (which uses a Linux VM).

**How to avoid:** The detection is best-effort — if `/proc/mounts` is absent (macOS native JVM), return `false` and skip the warning. Inside Docker on macOS, the check runs in the Linux VM context and works correctly. [ASSUMED — based on Docker Desktop architecture]

### Pitfall 7: `jvm.memory.used` Tag Requirements

**What goes wrong:** Calling `meterRegistry.get("jvm.memory.used").gauge()` without specifying tags throws `MeterNotFoundException` because Micrometer registers multiple gauges for different memory areas/IDs.

**How to avoid:** Filter by `area` tag: `meterRegistry.get("jvm.memory.used").tag("area", "heap").gauges()` returns all heap pool gauges; sum their values for total heap usage. [ASSUMED — standard Micrometer JVM metrics structure]

---

## Code Examples

### Verified: Counter Registration in BrokerMetricsService

```java
// Source: BrokerMetricsService.java (verified: codebase)
Counter.builder("mqtt.messages.received.total")
        .description("Total MQTT messages received")
        .register(meterRegistry);
```

### Verified: Existing Auth Denied Counter in ClientActor

```java
// Source: ClientActor.java lines 369, 470 (verified: codebase)
meterRegistry.counter("mqtt.auth.denied.total", "type", "subscribe").increment();
meterRegistry.counter("mqtt.auth.denied.total", "type", "publish").increment();
```

### Verified: EventListener Pattern from DefaultCredentialsInstaller

```java
// Source: DefaultCredentialsInstaller.java line 55-57 (verified: codebase)
@EventListener(ApplicationReadyEvent.class)
@Order(1)
public void installDefaultCredentials() { ... }
```

### Verified: /proc/mounts format (this machine)

```
/dev/nvme0n1p2 / ext4 rw,relatime,errors=remount-ro 0 0
overlay /var/lib/docker/overlay2/... overlay rw,...
```

Fields: `device mountpoint fstype options dump pass` — space-separated, stable format.

### Verified: Surefire PARANOID already set

```xml
<!-- pom.xml line 148 (verified: codebase) -->
<argLine>-Djava.library.path=${java.io.tmpdir} -Dio.netty.leakDetection.level=PARANOID</argLine>
```

All existing tests already run under PARANOID detection — any existing ByteBuf leak would have been caught already. The soak test extends this to long-duration load.

### Verified: Dockerfile Base Image and VOLUME

```dockerfile
# Dockerfile lines 24, 41 (verified: codebase)
FROM eclipse-temurin:17-jre-jammy
VOLUME ["/data/rocksdb"]
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Dropwizard Metrics | Micrometer | ~2018 (Spring Boot 2) | Unified API across registries |
| JMeter for load testing | In-process JUnit soak test | Per D-05 | No external tool dependency |
| Custom health endpoints | Spring Actuator | Spring Boot 1.x | `/actuator/prometheus` standard |

**Deprecated/outdated:**
- `DropwizardMeterRegistry`: Replaced by Micrometer's native registries; not applicable here
- Prometheus JMX exporter: For Spring Boot apps, use `micrometer-registry-prometheus` + Actuator instead

---

## Runtime State Inventory

No runtime state affected. This is a greenfield metrics/validation phase — no renaming, no migrations, no stored string identifiers being changed.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 17 | All compilation/tests | Yes | OpenJDK 17.0.18 | — |
| Maven 3.6+ | Build | Yes | 3.6.3 | — |
| Docker | ARM64 script (build step) | Yes | 29.4.0 | — |
| Docker buildx | Multi-arch image push | Yes | v0.33.0 | — |
| ARM64 device (Raspberry Pi / M-series / Graviton) | ARM64 validation script | NOT on this machine | — | Script documented for manual execution on ARM64 hardware |
| mosquitto-clients | ARM64 validation script | NOT verified | — | Script includes install instructions |

**Missing with no fallback:**
- ARM64 hardware — by design (D-13 specifies manual execution, not CI). Script documented for human to run.

**Missing with fallback:**
- `mosquitto-clients` — script includes `apt install mosquitto-clients` instruction in comments.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Spring Boot managed) |
| Config file | `lightweight/pom.xml` (Surefire plugin, line 138) |
| Quick run command | `mvn test -pl lightweight -Dtest=BrokerMetricsIT -DfailIfNoTests=false` |
| Full suite command | `mvn test -pl lightweight` |
| Soak test command | `mvn test -pl lightweight -Dgroups=soak -Dsoak.duration.minutes=60` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| OPS-01 | `/actuator/prometheus` returns `mqtt.messages.received.total` counter > 0 after publish | Integration | `mvn test -pl lightweight -Dtest=BrokerMetricsIT` | No — Wave 0 |
| OPS-01 | `mqtt.messages.delivered.total` counter > 0 after delivery | Integration | Same class | No — Wave 0 |
| OPS-01 | `mqtt.auth.success.total` increments on valid connect | Integration | Same class | No — Wave 0 |
| OPS-01 | `mqtt.auth.failure.total` increments on bad credentials | Integration | Same class | No — Wave 0 |
| OPS-01 | `mqtt.dispatch.queue.depth` gauge reports 0 when idle | Integration | Same class | No — Wave 0 |
| D-07 | Zero `LEAK:` log lines during 1-hour soak | Soak | `mvn test -pl lightweight -Dgroups=soak` | No — Wave 0 |
| D-08 | Final heap within 20% of steady-state baseline | Soak | Same | No — Wave 0 |
| D-11 | Startup warning logs when TLS disabled | Unit | `mvn test -pl lightweight -Dtest=StartupWarningServiceTest` | No — Wave 0 |
| D-12 | Volume-mount detection returns `true` for overlay path | Unit | Same | No — Wave 0 |

### Sampling Rate

- **Per task commit:** `mvn test -pl lightweight -Dtest=BrokerMetricsIT -DfailIfNoTests=false`
- **Per wave merge:** `mvn test -pl lightweight` (full suite, excludes soak)
- **Phase gate:** Full suite green before `/gsd-verify-work`; soak test run manually by developer

### Wave 0 Gaps

- [ ] `lightweight/src/test/java/.../metrics/BrokerMetricsIT.java` — covers all OPS-01 metric assertions
- [ ] `lightweight/src/test/java/.../install/StartupWarningServiceTest.java` — covers D-11 and D-12
- [ ] Soak test: `lightweight/src/test/java/.../soak/SoakTest.java` — covers D-07 and D-08
- [ ] `scripts/arm64-validate.sh` — covers ARM64 smoke test (D-14/D-15); manual execution only

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No — not changing auth logic, only instrumenting it | — |
| V3 Session Management | No | — |
| V4 Access Control | No | — |
| V5 Input Validation | No | — |
| V6 Cryptography | No | — |

**Note:** Auth success/failure counters must NOT log usernames in metric tags — high-cardinality tags cause Prometheus scrape performance issues and could leak sensitive data. Use only aggregate counters (no `username=` tag). [ASSUMED — standard Prometheus cardinality guidance]

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Micrometer Gauges hold weak references — object must be strongly referenced elsewhere | Architecture Patterns (Pattern 1), Pitfall 1 | Gauge shows NaN instead of real value; low risk since queue is a bean field |
| A2 | `jvm.memory.used` gauge requires `area=heap` tag filter to get heap-only values | Architecture Patterns (Pattern 8), Pitfall 7 | MeterNotFoundException at soak test assertion time; easy to fix |
| A3 | Paho v3 `MqttClient` does not hold a thread-per-client once connected | Pitfall 4 | Thread exhaustion at 500 concurrent clients; soak test would fail to create all clients |
| A4 | `/proc/mounts` behavior inside Docker Desktop (macOS) VM is equivalent to native Linux | Common Pitfalls (Pitfall 6) | Warning displayed when volume IS mounted (false positive); cosmetically annoying but harmless |
| A5 | `mosquitto-clients` available via `apt install` on Raspberry Pi OS / Ubuntu ARM64 | ARM64 script | Script fails on target device; user can substitute `docker run eclipse-mosquitto` as client |

**Verified claims in this session:** All metric gaps (GAP 1-4), `DefaultCredentialsInstaller` pattern, `TlsConfiguration`/`StorageConfiguration` shapes, existing Surefire PARANOID setting, Dockerfile base image and VOLUME, `/proc/mounts` format on this machine.

---

## Open Questions

1. **HiveMQ client for soak test (or Paho only)?**
   - What we know: Both Paho v3 and HiveMQ client are in test scope. Paho v5 also present.
   - What's unclear: Whether using both libraries in the soak test provides meaningful coverage vs. complexity.
   - Recommendation: Use Paho v3 for publishers/subscribers (simpler API, 500 clients). HiveMQ client is better for MQTT 5.0 feature testing — include a small batch of HiveMQ v5 clients in the soak mix to cover the MQTT 5.0 path.

2. **docker-compose.soak.yml — add or skip?**
   - What we know: D-05 says "no external tooling" but this is a convenience file, not a tool dependency.
   - What's unclear: User preference (left to Claude's discretion).
   - Recommendation: Add a minimal `docker-compose.soak.yml` that mounts a volume and sets PARANOID JVM arg — simplifies developer documentation.

3. **`mqtt.auth.denied.total` already in ClientActor — move to auth service layer?**
   - What we know: ACL denial counters (`"type","publish"` / `"type","subscribe"`) are in `ClientActor`. D-03 says auth success/failure should be in auth service. ACL denial is not the same as auth failure.
   - What's unclear: Should ACL denials stay in the actor (where ACL is evaluated) or move?
   - Recommendation: Keep ACL denial counters in `ClientActor` (correct location — ACL is evaluated there). Add new auth success/failure counters in `DefaultLightweightAuthService` (connection-level auth, different concern). No migration needed.

---

## Sources

### Primary (HIGH confidence)

- Codebase: `BrokerMetricsService.java` — confirmed `mqtt.messages.received.total` registered but never incremented
- Codebase: `ClientActor.java` — confirmed `processPublish()` calls `msgDispatcherService.dispatch()` without incrementing any counter
- Codebase: `ClientActor.java` — confirmed `processDeliver()` has no metrics calls
- Codebase: `DefaultLightweightAuthService.java` — confirmed no `MeterRegistry` dependency, no metric calls
- Codebase: `DefaultMsgDispatcherService.java` — confirmed `queue` field is `BlockingQueue`, `Queue::size` usable for Gauge
- Codebase: `pom.xml` line 148 — confirmed `PARANOID` already set in Surefire argLine
- Codebase: `DefaultCredentialsInstaller.java` — confirmed `@EventListener(ApplicationReadyEvent.class)` + `@Order(1)` pattern
- Codebase: `TlsConfiguration.java` — confirmed `isEnabled()` getter available
- Codebase: `StorageConfiguration.java` — confirmed `getPath()` returns `/data/rocksdb` default
- Codebase: `Dockerfile` lines 24, 41, 53-58 — confirmed base image, VOLUME, ENTRYPOINT
- OS: `/proc/mounts` — confirmed format `device mountpoint fstype options dump pass`

### Secondary (MEDIUM confidence)

- Spring Boot 3.5.3 Actuator: `management.prometheus.metrics.export.enabled=true` in yml enables `/actuator/prometheus` [VERIFIED: codebase yml]

### Tertiary (LOW confidence)

- Micrometer Gauge weak reference behavior — standard documented behavior [ASSUMED]
- Paho v3 thread model for idle connections — based on training knowledge [ASSUMED]

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries verified in pom.xml and codebase
- Architecture/patterns: HIGH — all patterns verified against existing codebase; assumptions flagged
- Pitfalls: MEDIUM — gaps 1-4 are HIGH; edge cases (Pitfall 3, 6, 7) are ASSUMED
- Gap analysis: HIGH — confirmed by direct code inspection

**Research date:** 2026-04-11
**Valid until:** 2026-05-11 (stable stack)
