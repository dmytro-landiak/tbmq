---
phase: 01-foundation
plan: "02"
subsystem: network-layer-and-observability
tags: [netty, epoll, nio, caffeine, prometheus, micrometer, smartlifecycle, connection-limit, tdd]
dependency_graph:
  requires: [01-01]
  provides: [netty-tcp-server, caffeine-cache, prometheus-metrics, connection-limit-enforcement]
  affects: [all-subsequent-plans]
tech_stack:
  added:
    - Netty EpollEventLoopGroup / NioEventLoopGroup (Epoll detection at runtime)
    - Netty ChannelHandler.Sharable connection counter with AtomicInteger gauge
    - Caffeine CaffeineCacheManager with recordStats() (3 pre-registered caches)
    - CaffeineCacheMetrics.monitor() explicit registration per D-16
    - Micrometer prometheus export (management.prometheus.metrics.export.enabled=true)
  patterns:
    - SmartLifecycle phase 0 for Netty (after RocksDB MIN_VALUE, stops before it)
    - @ChannelHandler.Sharable with AtomicInteger for shared connection counting
    - MeterBinder bean for explicit cache metric registration
    - spring.config.name=tbmq-lightweight in @SpringBootTest properties for correct yml loading
    - management.server.port=0 + @LocalManagementPort for CI-safe actuator testing
key_files:
  created:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/NettyConfiguration.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttTcpServerBootstrap.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttChannelInitializer.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/ConnectionCountHandler.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/cache/CacheConfiguration.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsService.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/server/NettyServerBootstrapTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/cache/CacheConfigurationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/GracefulShutdownTest.java
  modified:
    - lightweight/src/main/resources/tbmq-lightweight.yml
decisions:
  - Add spring.config.name=tbmq-lightweight to all @SpringBootTest properties — Spring Boot tests do not run SpringApplication.main() so the updateArguments() config name injection is skipped; tests defaulted to application.yml (not found) and missed all tbmq-lightweight.yml settings
  - Add management.prometheus.metrics.export.enabled=true to tbmq-lightweight.yml — Spring Boot 3.5 defaults this to false; must be explicit for /actuator/prometheus to load
  - Use matching tags in CaffeineCacheMetrics.monitor() call — Prometheus requires all meters with the same name to have identical tag key sets; Spring Boot uses [cache, cache.manager, name] while our initial explicit registration used only [cache], causing WARN-level collisions
metrics:
  duration: "9 minutes"
  completed: "2026-04-03"
  tasks_completed: 2
  tasks_total: 2
  files_created: 9
  files_modified: 1
---

# Phase 01 Plan 02: Netty TCP Server, Caffeine Cache, and Prometheus Metrics Summary

**One-liner:** Netty TCP server at phase 0 SmartLifecycle with Epoll/NIO detection, AtomicInteger connection counting gauge, configurable max-connections limit, Caffeine cache manager with 3 pre-registered caches and explicit CaffeineCacheMetrics.monitor() per D-16, and Prometheus endpoint verified via GracefulShutdownTest.

## What Was Built

A complete network and observability layer for the TBMQ Lightweight broker:
1. **Netty TCP server** — SmartLifecycle at phase 0, auto-detects Epoll on Linux (NIO fallback), exposes `getLocalPort()` for test port discovery, shuts down gracefully with `shutdownGracefully(0, 5, SECONDS)`
2. **Connection limit enforcement** — `ConnectionCountHandler` (sharable) with `AtomicInteger` tracks connections and rejects excess via `ctx.close()` when `current > maxConnections`
3. **Caffeine cache** — 3 pre-registered caches (`credentials`, `acl_rules`, `sessions`) with `recordStats()` and explicit `CaffeineCacheMetrics.monitor()` per D-16
4. **Prometheus metrics** — `/actuator/prometheus` returns JVM metrics, `mqtt_connections_active` gauge, and `cache_gets_total` counters; `/actuator/health` returns UP

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Netty TCP server bootstrap with Epoll/NIO, connection limit handler, and channel initializer | f10191de7 | NettyConfiguration.java, MqttTcpServerBootstrap.java, MqttChannelInitializer.java, ConnectionCountHandler.java, NettyServerBootstrapTest.java |
| 2 | Caffeine cache configuration with explicit CaffeineCacheMetrics, Prometheus metrics, and graceful shutdown integration test | 81e56092a | CacheConfiguration.java, BrokerMetricsService.java, CacheConfigurationTest.java, GracefulShutdownTest.java, tbmq-lightweight.yml |

## Verification Results

- `mvn test -q`: PASSED — 21 tests total (13 from Plan 01 + 8 from Plan 02)
- `NettyServerBootstrapTest` (5 tests): getPhase returns 0, TCP connect, connection count tracking, connection limit enforcement, isRunning
- `CacheConfigurationTest` (4 tests): CaffeineCacheManager type, cache names, put/get, D-16 metrics registration
- `GracefulShutdownTest` (4 tests): lifecycle ordering, both services running, prometheus endpoint (jvm + mqtt + cache metrics), health endpoint UP

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Spring @SpringBootTest missing config name — yml properties not loaded**
- **Found during:** Task 2 - GracefulShutdownTest testPrometheusEndpoint_returnsMetrics
- **Issue:** `@SpringBootTest` does not run `TbmqLightweightApplication.main()` so `updateArguments()` never injects `--spring.config.name=tbmq-lightweight`. Tests resolved to `application.yml` (which doesn't exist), missing all `tbmq-lightweight.yml` settings including `management.prometheus.metrics.export.enabled=true`. Result: 404 on `/actuator/prometheus` because `PrometheusMetricsExportAutoConfiguration` was disabled by default.
- **Fix:** Added `spring.config.name=tbmq-lightweight` to all `@SpringBootTest(properties = {...})` blocks in NettyServerBootstrapTest, CacheConfigurationTest, and GracefulShutdownTest.
- **Files modified:** All 3 test files
- **Commit:** 81e56092a

**2. [Rule 2 - Missing functionality] Added management.prometheus.metrics.export.enabled=true to yml**
- **Found during:** Task 2 - debugging 404 on prometheus endpoint
- **Issue:** Spring Boot 3.5 defaults `management.prometheus.metrics.export.enabled` to false (via `management.defaults.metrics.export.enabled`). Without explicit opt-in, `PrometheusMetricsExportAutoConfiguration` is skipped and the `/actuator/prometheus` endpoint is not registered.
- **Fix:** Added `management.prometheus.metrics.export.enabled: true` to `tbmq-lightweight.yml` under the `management:` section.
- **Files modified:** `tbmq-lightweight.yml`
- **Commit:** 81e56092a

**3. [Rule 1 - Bug] Fixed CaffeineCacheMetrics tag collision in Prometheus registry**
- **Found during:** Task 2 - first test run after prometheus was enabled
- **Issue:** Prometheus requires all meters with the same name to have an identical tag key set. Spring Boot's `CacheMetricsAutoConfiguration` registers `cache_size` with tags `[cache, cache.manager, name]`. Our explicit `CaffeineCacheMetrics.monitor(registry, cache, cacheName)` registered with only `[cache]`. This caused `PrometheusMeterRegistry` WARN-level errors and potentially incomplete metrics.
- **Fix:** Updated the `MeterBinder` bean in `CacheConfiguration` to pass matching tags: `[cache=cacheName, cache.manager=cacheManager, name=cacheName]` — same as Spring Boot's `CaffeineCacheMeterBinderProvider`.
- **Files modified:** `CacheConfiguration.java`
- **Commit:** 81e56092a

## Key Technical Decisions

1. **spring.config.name=tbmq-lightweight in @SpringBootTest**: All integration tests must include this property. Spring's `@SpringBootTest` finds `TbmqLightweightApplication` via component scan but does NOT run `main()` — the config name injection in `updateArguments()` is a runtime-only mechanism.

2. **management.server.port=0 + @LocalManagementPort pattern**: Using a separate management port with random assignment is required for CI where port 8083 may be busy. `TestRestTemplate` must be constructed with the actual management port — the default `@Autowired TestRestTemplate` uses the main server port.

3. **Explicit prometheus export opt-in**: Spring Boot 3.4+ changed prometheus export to be opt-in via `management.prometheus.metrics.export.enabled=true`. This must be in the YAML (or test properties) — it is not automatically enabled by having `micrometer-registry-prometheus` on the classpath.

4. **Tag alignment with Spring Boot cache metrics**: `CaffeineCacheMetrics.monitor()` call must use the same tag keys as Spring Boot's auto-config (`cache`, `cache.manager`, `name`) to avoid Prometheus tag collision errors.

## Known Stubs

- `mqtt.messages.received.total` counter in `BrokerMetricsService` — registered but never incremented. Will be connected to PUBLISH handler in Phase 2 (MQTT protocol processing).
- Phase 1 pipeline in `MqttChannelInitializer` has only `IdleStateHandler` (disabled, 0-timeout) and `ConnectionCountHandler`. No MQTT codec — will be added in Phase 2.

## Self-Check: PASSED

All created files verified present. Both commits (f10191de7, 81e56092a) confirmed in git log.
