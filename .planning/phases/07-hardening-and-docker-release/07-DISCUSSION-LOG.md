# Phase 7: Hardening and Docker Release - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-11
**Phase:** 07-hardening-and-docker-release
**Areas discussed:** Metrics completeness, Soak test strategy, Startup warnings, ARM64 validation

---

## Metrics Completeness

### Metrics surface scope

| Option | Description | Selected |
|--------|-------------|----------|
| Success criteria only | Add only what SC-1 requires: message rates in/out, auth success/failure, dispatch queue depth | ✓ |
| Operational completeness | Also add subscription count, retained message count, QoS distribution, keep-alive timeout count, client takeover count | |
| You decide | Claude picks the right balance | |

**User's choice:** Success criteria only
**Notes:** Keep minimal for R1, expand later.

### Dispatch queue depth metric type

| Option | Description | Selected |
|--------|-------------|----------|
| Gauge | Simple current-size gauge, matches ConnectionCountHandler pattern | ✓ |
| Gauge + high-water-mark | Current-size gauge plus peak queue size since startup | |
| You decide | Claude picks | |

**User's choice:** Gauge
**Notes:** None

### Auth counter placement

| Option | Description | Selected |
|--------|-------------|----------|
| Auth service layer | Increment in auth service where result is computed; covers all transports automatically | ✓ |
| ClientActor processConnect | Increment in actor after auth result is known | |
| You decide | Claude picks based on current code | |

**User's choice:** Auth service layer
**Notes:** None

---

## Soak Test Strategy

### Load generation tool

| Option | Description | Selected |
|--------|-------------|----------|
| Custom Java test | JUnit-based soak test using existing Paho/HiveMQ client libraries; no external tooling | ✓ |
| Docker Compose harness | Separate docker-compose with load generator container | |
| Script + Paho CLI | Shell script spawning Paho CLI clients | |
| You decide | Claude picks | |

**User's choice:** Custom Java test
**Notes:** None

### Load profile

| Option | Description | Selected |
|--------|-------------|----------|
| Moderate steady-state | 500 clients, 1,000 msg/sec, QoS mix, configurable duration (1h CI / 24h manual) | ✓ |
| Production-scale | 5,000+ clients, 10,000 msg/sec | |
| Configurable via env vars | All params configurable, default to moderate | |
| You decide | Claude picks | |

**User's choice:** Moderate steady-state
**Notes:** None

### RSS stability validation

| Option | Description | Selected |
|--------|-------------|----------|
| JVM heap via MeterRegistry | Sample jvm.memory.used from Micrometer, assert within 20% of baseline | ✓ |
| Container RSS via /proc | Read /proc/self/status VmRSS; catches native memory leaks | |
| Both JVM heap + RSS | Track both for managed and native leak detection | |
| You decide | Claude picks | |

**User's choice:** JVM heap via MeterRegistry
**Notes:** None

---

## Startup Warnings

### Warning presentation format

| Option | Description | Selected |
|--------|-------------|----------|
| LOG.WARN with banner | log.warn() with prominent multi-line bordered block; visible in docker logs | ✓ |
| Plain log.warn lines | Simple one-line log.warn per misconfiguration | |
| You decide | Claude picks | |

**User's choice:** LOG.WARN with banner
**Notes:** None

### Warning timing

| Option | Description | Selected |
|--------|-------------|----------|
| ApplicationReadyEvent | Run checks after all SmartLifecycle components started; last log lines before "Started in X seconds" | ✓ |
| SmartLifecycle (highest phase) | Dedicated SmartLifecycle bean; more explicit but unnecessary | |
| You decide | Claude picks | |

**User's choice:** ApplicationReadyEvent
**Notes:** None

### Volume mount detection

| Option | Description | Selected |
|--------|-------------|----------|
| Check Docker overlay filesystem | Read /proc/mounts, check if path filesystem is 'overlay' | ✓ |
| Env var sentinel | Require TBMQ_VOLUME_MOUNTED=true | |
| You decide | Claude picks | |

**User's choice:** Check Docker overlay filesystem
**Notes:** None

---

## ARM64 Validation

### Validation approach

| Option | Description | Selected |
|--------|-------------|----------|
| Manual test script | Shell script (scripts/arm64-validate.sh) runnable on any ARM64 device | ✓ |
| CI with self-hosted runner | GitHub Actions self-hosted ARM64 runner | |
| QEMU in CI + manual ARM64 | QEMU-emulated in CI plus documented manual procedure | |
| You decide | Claude picks | |

**User's choice:** Manual test script
**Notes:** None

### Validation scope

| Option | Description | Selected |
|--------|-------------|----------|
| Core smoke test | Start, connect (TCP), pub/sub, RocksDB persistence across restart; ~5 min | ✓ |
| Full transport coverage | All transports, auth modes, retained messages, RocksDB; ~15 min | |
| You decide | Claude picks | |

**User's choice:** Core smoke test
**Notes:** None

---

## Claude's Discretion

- Exact metric names and tag labels
- Soak test JUnit class structure and assertion mechanics
- Startup warning banner format and exact wording
- ARM64 validation script implementation details
- Whether to add docker-compose.soak.yml
- Soak test baseline sampling strategy

## Deferred Ideas

None — discussion stayed within phase scope
