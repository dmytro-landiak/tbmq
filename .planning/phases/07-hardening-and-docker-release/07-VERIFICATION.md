---
phase: 07-hardening-and-docker-release
verified: 2026-04-11T16:02:38Z
revisited: 2026-04-30T13:50:00Z
status: human_needed
score: 3/4 must-haves verified (SC-3 ARM64 hardware run remaining; SC-2 closed as optional 2026-04-30, SC-4 user-confirmed 2026-04-30)
overrides_applied: 0
human_verification:
  - test: "Execute arm64-validate.sh on real ARM64 hardware (not QEMU)"
    expected: "Script prints 'ARM64 validation PASSED' after completing all 5 steps: container start, subscribe, publish receipt, container restart, credential persistence"
    why_human: "Roadmap SC-3 explicitly requires execution on real ARM64 hardware. Plan 02 Task 3 was auto-approved — no human ran the script on a physical device. QEMU emulation does not satisfy this criterion."
    result: pending
  - test: "Observe startup warning banner in docker logs output"
    expected: "Running 'docker logs <container>' shows the WARN-level '===...TBMQ LIGHTWEIGHT — STARTUP WARNINGS...' banner with TLS, retained-message, and (if applicable) overlay-filesystem warnings"
    why_human: "Integration tests use direct MeterRegistry queries, not the actual docker logs output. SC-4 says 'logs a clear startup warning' — the log format and visibility in real docker logs requires visual confirmation."
    result: passed (2026-04-30 — user-confirmed banner visible in real `docker logs`)
---

# Phase 7: Hardening and Docker Release Verification Report

**Phase Goal:** The complete broker passes a 24-hour soak test with zero memory leaks or ByteBuf warnings, the Docker image is validated on real ARM64 hardware, Prometheus metrics are confirmed accurate under load, and the broker emits clear startup warnings for common misconfigurations before public release
**Verified:** 2026-04-11T16:02:38Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (from Roadmap Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| SC-1 | `GET /actuator/prometheus` returns accurate metrics under sustained load: connection count, message rates (in/out), auth success/failure counts, RocksDB read latency, and dispatch queue depth | VERIFIED | All 5 counters/gauges registered and wired. BrokerMetricsIT: 5 tests pass. ConnectionCountHandler (mqtt.connections.active), DefaultRocksDbStorage (rocksdb.read.latency), all new metrics confirmed via passing integration tests. |
| SC-2 | A 24-hour soak test with simulated production load shows zero Netty ByteBuf leak warnings (PARANOID detection) and stable container RSS (no unbounded growth) | VERIFIED (canonical evidence: 1-minute smoke run; 24h run closed as OPTIONAL on 2026-04-30) | SoakTest.java exists with @Tag("soak"), PARANOID ListAppender, heap stability assertion (< 1.20x baseline). 1-minute smoke run passed per SUMMARY (140,008 msgs, 0 leaks, 72MB final vs 86MB baseline). Per user decision 2026-04-30, the 24-hour run is treated as optional — correctness is duration-independent and the smoke run is the canonical SC-2 evidence. |
| SC-3 | The Docker image runs correctly on a real ARM64 device (not QEMU emulation); the broker starts, accepts connections, and RocksDB persists data | NEEDS HUMAN | scripts/arm64-validate.sh exists, is executable, passes bash syntax validation. Plan 02 Task 3 checkpoint was "auto-approved" — no physical ARM64 device test was run. Roadmap explicitly excludes QEMU. |
| SC-4 | The broker logs a clear startup warning when TLS is not configured, when `/data/rocksdb` is not volume-mounted, and when retained messages are in-memory only | VERIFIED (banner observed in `docker logs` 2026-04-30) | StartupWarningService.java: @Order(2) @EventListener, all 3 warning conditions implemented. Unit tests: 6 pass. Banner visibility in real `docker logs` user-confirmed 2026-04-30. WR-04 (`5d25fbf3d`) split the retained-in-memory line out as INFO so the `if (!warnings.isEmpty())` banner guard is non-trivial again. |

**Score:** 3/4 truths verified (SC-3 ARM64 hardware run remaining; SC-2 closed as optional 2026-04-30; SC-4 banner user-confirmed 2026-04-30)

### Deferred Items

None — Phase 7 is the final milestone phase. No later phases exist to defer items to.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `lightweight/src/main/java/.../metrics/BrokerMetricsService.java` | Registration of mqtt.messages.delivered.total counter | VERIFIED | Contains `Counter.builder("mqtt.messages.delivered.total")` at line 43. Also registers mqtt.messages.received.total and mqtt.dispatch.dropped.total. |
| `lightweight/src/main/java/.../security/auth/DefaultLightweightAuthService.java` | Auth success/failure counter increments | VERIFIED | Contains `private final MeterRegistry meterRegistry`, `@PostConstruct void initMetrics()`, `authSuccessCounter.increment()` and `authFailureCounter.increment()` across all auth paths (anonymous, SSL, basic). |
| `lightweight/src/main/java/.../actors/client/ClientActor.java` | Messages received and delivered counter increments | VERIFIED | Line 530: `meterRegistry.counter("mqtt.messages.received.total").increment()` in processPublish(); line 573 in processPubRel(); line 663: `meterRegistry.counter("mqtt.messages.delivered.total").increment()` in processDeliver(). |
| `lightweight/src/main/java/.../service/dispatch/DefaultMsgDispatcherService.java` | Queue depth Gauge registration | VERIFIED | Lines 95-97: `Gauge.builder("mqtt.dispatch.queue.depth", queue, Queue::size)` in start() after queue creation. |
| `lightweight/src/main/java/.../install/StartupWarningService.java` | Startup warning service with TLS, volume-mount, and retained-msg checks | VERIFIED | Contains `@EventListener(ApplicationReadyEvent.class)`, `@Order(2)`, `TBMQ LIGHTWEIGHT`, `isRocksDbPathOverlay`, `"overlay".equals(bestFsType)`, all 3 warning message strings. |
| `lightweight/src/test/java/.../metrics/BrokerMetricsIT.java` | Integration test verifying all metrics via MeterRegistry | VERIFIED | 5 test methods covering all required metrics. Tests query MeterRegistry directly. All pass (5 tests, 0 failures). |
| `lightweight/src/test/java/.../install/StartupWarningServiceTest.java` | Unit test for startup warning conditions | VERIFIED | 6 test methods with MockitoExtension. All pass. |
| `lightweight/src/test/java/.../soak/SoakTest.java` | Long-running soak test with ByteBuf leak detection and heap stability assertion | VERIFIED | Contains `@Tag("soak")`, `TOTAL_CLIENTS = 500`, `TARGET_MSG_PER_SEC = 1000`, `soak.duration.minutes` system property, `LEAK:` check, heap stability `< baseline * 1.20`, `jvm.memory.used` with area=heap. |
| `scripts/arm64-validate.sh` | ARM64 Docker image validation script | VERIFIED (static) | File exists, is executable (`test -x` passes), bash syntax valid (`bash -n` passes), contains all required elements: `set -euo pipefail`, `mosquitto_pub`, `mosquitto_sub`, `arm64-ok`, `docker stop`/`docker rm`, `ARM64 validation PASSED`, `trap cleanup EXIT`, `Started TbmqLightweightApplication`. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| ClientActor.java | meterRegistry | counter increment in processPublish and processPubRel | WIRED | `meterRegistry.counter("mqtt.messages.received.total").increment()` at lines 530 and 573 |
| ClientActor.java | meterRegistry | counter increment in processDeliver | WIRED | `meterRegistry.counter("mqtt.messages.delivered.total").increment()` at line 663 |
| DefaultLightweightAuthService.java | meterRegistry | counter increment in authenticate() | WIRED | `authSuccessCounter.increment()` and `authFailureCounter.increment()` before every AuthResult return across all auth paths |
| DefaultMsgDispatcherService.java | meterRegistry | Gauge registration in start() | WIRED | `Gauge.builder("mqtt.dispatch.queue.depth", queue, Queue::size).register(meterRegistry)` at line 95 |
| arm64-validate.sh | Docker image | docker run + mosquitto_pub/sub | VERIFIED (static) | Script contains all required commands; execution on real hardware needs human |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|--------------|--------|--------------------|--------|
| BrokerMetricsIT.java | `meterRegistry.get("mqtt.messages.received.total").counter().count()` | Counter incremented in ClientActor.processPublish() on real MQTT PUBLISH | Yes — 5 passing tests confirm real increments | FLOWING |
| BrokerMetricsIT.java | `meterRegistry.get("mqtt.auth.success.total").counter().count()` | Counter incremented in DefaultLightweightAuthService.authenticate() on real CONNECT | Yes — test connects with valid credentials and asserts increment | FLOWING |
| BrokerMetricsIT.java | `meterRegistry.get("mqtt.dispatch.queue.depth").gauge().value()` | Gauge reflects `queue.size()` on real BlockingQueue | Yes — idle test asserts 0.0 (queue empty) | FLOWING |
| SoakTest.java | `mqtt.messages.received.total > 0` after 1-minute run | Real MQTT traffic from 500 clients | Yes — SUMMARY confirms 140,008 messages delivered in 1-minute run | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| BrokerMetricsIT — all 5 metric tests pass | `mvn test -Dtest=BrokerMetricsIT` | Tests run: 5, Failures: 0, Errors: 0 | PASS |
| StartupWarningServiceTest — all 6 unit tests pass | `mvn test -Dtest=StartupWarningServiceTest` | Tests run: 6, Failures: 0, Errors: 0 | PASS |
| pom.xml excludedGroups prevents soak in default run | `grep -n surefire.excludedGroups pom.xml` | `<surefire.excludedGroups>soak</surefire.excludedGroups>` and `<excludedGroups>${surefire.excludedGroups}</excludedGroups>` found | PASS |
| arm64-validate.sh bash syntax valid | `bash -n scripts/arm64-validate.sh` | exit 0 | PASS |
| arm64-validate.sh is executable | `test -x scripts/arm64-validate.sh` | exit 0 | PASS |
| ARM64 on real hardware | Manual — requires ARM64 device | Not executed | SKIP (human required) |
| 24-hour soak test | `mvn test -Dgroups=soak -Dsoak.duration.minutes=1440` | 1-minute smoke run passed; 24-hour run not executed | CLOSED as OPTIONAL (2026-04-30 user decision — 1-min smoke is canonical SC-2 evidence) |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| OPS-01 | 07-01-PLAN.md, 07-02-PLAN.md | Broker exposes Prometheus-compatible `/metrics` endpoint with connection counts, message rates, and error counters | SATISFIED | Prometheus endpoint configured (`/actuator/prometheus` with include=prometheus). All required metrics registered and incrementing. "Under sustained load" validation: 1-minute soak passed (canonical SC-2 evidence per 2026-04-30 decision). ARM64 hardware run remaining (SC-3, tracked separately). |

**Requirement OPS-01 note:** REQUIREMENTS.md was updated 2026-04-30 (commit `4c039b49e`) to tick OPS-01 and the Traceability row. Metric infrastructure is fully implemented and passing tests; "under load" validation is the 1-minute smoke run (SC-2 closed as optional). SC-3 ARM64 hardware run remains as the only Phase 7 release gate.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `SoakTest.java` | 238 | `{:.0f}` SLF4J placeholder (invalid format — Python/C syntax) | Warning | ~~Log output will print literal `{:.0f}` and format number as Java double toString. Test still passes.~~ **FIXED 2026-04-30 (`46143e19d`)** — replaced with `{}` and `(long)` cast. |
| `StartupWarningService.java` | 79 | `if (!warnings.isEmpty())` is dead code — retained-message warnings always added, so banner always emits | Warning | ~~Operators with correctly configured brokers always see WARNING banner; alert fatigue risk.~~ **NOT A DEFECT** — already corrected by WR-04 (`5d25fbf3d`); the retained-in-memory line is `log.info(...)` at line 76, the WARN banner at line 79 is correctly guarded. |
| `DefaultMsgDispatcherService.java` | 81 | `queue.offer(msg)` before null check — NPE if dispatch() called before start() | Warning | CR-02 from code review. Silent message drop in edge case. Does not affect normal operation. |
| `ClientActor.java` | 203-215 | Displaced actor in session takeover not stopped — QoS state maps leak until GC | Warning | CR-01 from code review. Memory leak during high-reconnect scenarios. |
| `BrokerMetricsService.java` | 1 | Missing Apache 2.0 license header | Info | ~~Inconsistency with all other production Java files in the module.~~ **FIXED 2026-04-30 (`0d906f4e9`)** — canonical 15-line header added. |

### Human Verification Required

#### 1. ARM64 Hardware Validation (Roadmap SC-3)

**Test:** Run `./scripts/arm64-validate.sh [image:tag]` on a real ARM64 device (Raspberry Pi 4/5, macOS M-series with Docker Desktop, or AWS Graviton instance). The Docker image must be built and pushed first with `--platform linux/arm64`.

**Expected:**
- Step 1: Container starts and logs `Started TbmqLightweightApplication` within 30 seconds
- Step 2-3: `mosquitto_sub` receives the `arm64-ok` message published by `mosquitto_pub`
- Step 4-5: After container restart with same volume mount, `mosquitto_pub -u tbmq -P tbmq` succeeds (credentials persisted in RocksDB)
- Final output: `ARM64 validation PASSED`

**Why human:** Roadmap SC-3 explicitly requires "real ARM64 device (not QEMU emulation)". Plan 02 Task 3 was marked "auto-approved" in the SUMMARY — no human executed this test. This is a pre-release gate per the phase goal.

#### 2. Startup Warning Banner Visibility in Docker Logs

**Status:** PASSED 2026-04-30 — user observed the banner in real `docker logs` output.

**Test:** Run `docker run -p 1883:1883 thingsboard/tbmq-lightweight` (without TLS configured, without a volume mount), then `docker logs <container>`.

**Expected:** The WARN-level output contains:
```
======================================================================
  TBMQ LIGHTWEIGHT — STARTUP WARNINGS
======================================================================
  TLS is not configured. MQTT traffic is unencrypted.
  ...
  Retained messages are stored in-memory only.
  ...
======================================================================
```

**Why human:** Integration tests use direct MeterRegistry queries in an embedded Spring context. The actual appearance and readability of the WARNING banner in `docker logs` output requires visual inspection in a real Docker environment.

### Gaps Summary

No automated gaps found — all code artifacts exist, are substantive, and are wired. Tests pass.

The only remaining item before "public release readiness" is **SC-3 (ARM64 hardware validation)**. The validation script is complete and syntax-valid, but Roadmap SC-3 explicitly requires execution on real ARM64 hardware, and Plan 02 Task 3 was auto-approved without running the script.

SC-4 (banner visibility) was user-confirmed on 2026-04-30. The 24-hour soak (SC-2) was closed as OPTIONAL on 2026-04-30 (user decision) — the 1-minute smoke run is the canonical SC-2 evidence; correctness is duration-independent.

---

_Verified: 2026-04-11T16:02:38Z_
_Revisited: 2026-04-30T13:50:00Z (SC-2 closed optional, SC-4 user-confirmed; STATUS.md follow-up pass)_
_Verifier: Claude (gsd-verifier)_
