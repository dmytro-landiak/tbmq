---
phase: 07-hardening-and-docker-release
plan: 01
subsystem: lightweight-broker
tags: [metrics, prometheus, startup-warnings, observability, ops]
dependency_graph:
  requires: []
  provides:
    - mqtt.messages.received.total counter incremented in ClientActor
    - mqtt.messages.delivered.total counter incremented in ClientActor
    - mqtt.auth.success.total counter incremented in DefaultLightweightAuthService
    - mqtt.auth.failure.total counter incremented in DefaultLightweightAuthService
    - mqtt.dispatch.queue.depth gauge registered in DefaultMsgDispatcherService
    - StartupWarningService logging TLS/volume/retained-msg warnings on ApplicationReadyEvent
  affects:
    - BrokerMetricsService
    - DefaultLightweightAuthService
    - ClientActor
    - DefaultMsgDispatcherService
tech_stack:
  added: []
  patterns:
    - Micrometer Counter.builder() registration with increment() at call sites
    - Micrometer Gauge.builder() with Queue::size method reference for live queue depth
    - @PostConstruct counter initialization in @RequiredArgsConstructor service
    - @EventListener(ApplicationReadyEvent) @Order(2) for post-startup warnings
    - /proc/mounts longest-prefix mount detection for overlay filesystem check
key_files:
  created:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/install/StartupWarningService.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsIT.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/install/StartupWarningServiceTest.java
  modified:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/DefaultLightweightAuthService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java
    - lightweight/pom.xml
decisions:
  - Auth counters use zero-tag aggregate counters only (no username/clientId tags) — prevents high-cardinality label leakage per T-07-02
  - Gauge registered against BlockingQueue field (strong reference in service bean) — prevents Micrometer weak-reference GC issue
  - Auth counters initialized in @PostConstruct (not constructor) — Counter.builder().register() requires MeterRegistry to be ready
  - isRocksDbPathOverlay() is package-private for unit testability without filesystem mocking
metrics:
  duration: ~35 minutes
  completed: 2026-04-11
  tasks_completed: 2
  files_modified: 9
---

# Phase 7 Plan 1: Prometheus Metrics Surface and Startup Warnings Summary

**One-liner:** Five Prometheus metrics fully wired (received/delivered counters, auth success/failure counters, dispatch queue depth gauge) plus startup warning banner for TLS/volume/retained-msg misconfigurations.

## Tasks Completed

| Task | Description | Commit | Status |
|------|-------------|--------|--------|
| 1 | Complete Prometheus metrics surface (D-01 to D-04) | 5cc397826 | Done |
| 2 | Startup warning service (D-09 to D-12) | 33cc2fe22 | Done |

## What Was Built

### Task 1: Prometheus Metrics Surface

**BrokerMetricsService** — Added registration of `mqtt.messages.delivered.total` counter alongside the existing `mqtt.messages.received.total`.

**DefaultLightweightAuthService** — Added `MeterRegistry` constructor injection, `@PostConstruct` initialization of `authSuccessCounter` and `authFailureCounter`, and increments before every `AuthResult.success()` and `AuthResult.failure()` return across all auth paths (anonymous, SSL/X.509, basic username/password).

**ClientActor** — Added `meterRegistry.counter("mqtt.messages.received.total").increment()` in:
- `processPublish()` after ACL pass, before dispatch (covers QoS 0 and QoS 1)
- `processPubRel()` inside `if (stored instanceof PublishMsg)` block, before dispatch (covers QoS 2)

Added `meterRegistry.counter("mqtt.messages.delivered.total").increment()` at the end of `processDeliver()` covering all QoS paths (0, 1, 2).

**DefaultMsgDispatcherService** — Added `Gauge.builder("mqtt.dispatch.queue.depth", queue, Queue::size)` registration in `start()` after queue creation. Uses `Queue::size` method reference to give Micrometer a live view of the queue depth.

**pom.xml** — Added `<excludedGroups>soak</excludedGroups>` to `maven-surefire-plugin` configuration so `@Tag("soak")` tests are excluded from `mvn test`.

**BrokerMetricsIT** — New integration test class with 5 test methods verifying all metrics via direct `MeterRegistry` queries. All 5 pass.

### Task 2: Startup Warning Service

**StartupWarningService** — New `@Service` class at `org.thingsboard.mqtt.broker.lightweight.install`. Implements `@EventListener(ApplicationReadyEvent.class)` at `@Order(2)` (runs after `DefaultCredentialsInstaller` at `@Order(1)`).

Three warning conditions:
1. `!tlsConfig.isEnabled()` → warns MQTT traffic is unencrypted, suggests `TBMQ_TLS_ENABLED=true`
2. `isRocksDbPathOverlay(storageConfig.getPath())` → warns credentials will be lost on container removal
3. Always warns that retained messages are in-memory only (R1 constraint)

Warning output is a bordered `===` banner at `log.warn()` level containing "TBMQ LIGHTWEIGHT — STARTUP WARNINGS".

`isRocksDbPathOverlay()` reads `/proc/mounts`, finds the longest-prefix matching mount point for the data path, and returns `true` if that mount's filesystem type is `"overlay"`. Returns `false` if `/proc/mounts` doesn't exist (non-Linux) or on any `IOException`.

**StartupWarningServiceTest** — 5 unit tests using `@ExtendWith(MockitoExtension.class)` with mock `TlsConfiguration` and `StorageConfiguration`. All pass.

## Verification Results

```
mvn test -pl lightweight -Dtest=BrokerMetricsIT     → Tests run: 5, Failures: 0, Errors: 0
mvn test -pl lightweight -Dtest=StartupWarningServiceTest → Tests run: 6, Failures: 0, Errors: 0
mvn test -pl lightweight                            → Tests run: 152, Failures: 0, Errors: 0, Skipped: 3
```

Grep confirms all metric names present in source:
- `mqtt.messages.received.total` — BrokerMetricsService (register) + ClientActor (2x increment)
- `mqtt.messages.delivered.total` — BrokerMetricsService (register) + ClientActor (increment)
- `mqtt.auth.success.total` — DefaultLightweightAuthService (register + increment)
- `mqtt.auth.failure.total` — DefaultLightweightAuthService (register + increment)
- `mqtt.dispatch.queue.depth` — DefaultMsgDispatcherService (Gauge)

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — all metrics are wired to real protocol events and verified by passing integration tests.

## Threat Flags

None — no new network endpoints, auth paths, or trust boundaries introduced. Auth counters use zero-tag aggregates as required by T-07-02.

## Self-Check: PASSED
