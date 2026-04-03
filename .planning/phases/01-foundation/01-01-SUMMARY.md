---
phase: 01-foundation
plan: "01"
subsystem: storage-foundation
tags: [rocksdb, spring-boot, lifecycle, embedded-storage, tdd]
dependency_graph:
  requires: []
  provides: [maven-project, spring-boot-context, rocksdb-storage, column-families]
  affects: [all-subsequent-plans]
tech_stack:
  added:
    - Spring Boot 3.5.3 (spring-boot-starter-parent)
    - RocksDB 9.7.4 (rocksdbjni)
    - Netty 4.1.122.Final (pinned via netty.version property)
    - Caffeine (managed by Spring Boot)
    - Micrometer Prometheus registry 1.15.1
    - Lombok
  patterns:
    - SmartLifecycle for ordered startup/shutdown
    - ConfigurationProperties for env var binding
    - Interface + Default prefix implementation pattern
    - TDD with @TempDir for file-system isolation
key_files:
  created:
    - lightweight/pom.xml
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/TbmqLightweightApplication.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/StorageConfiguration.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/storage/rocksdb/RocksDbColumnFamily.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/storage/rocksdb/RocksDbStorage.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/storage/rocksdb/DefaultRocksDbStorage.java
    - lightweight/src/main/resources/tbmq-lightweight.yml
    - lightweight/src/main/resources/logback-spring.xml
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/TbmqLightweightApplicationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/storage/RocksDbStorageTest.java
  modified: []
decisions:
  - Add @EnableAutoConfiguration to TbmqLightweightApplication to enable Spring Boot auto-config for MeterRegistry
  - Set running=true before schema_version initialization so put/get guards pass during start()
metrics:
  duration: "6 minutes"
  completed: "2026-04-03"
  tasks_completed: 2
  tasks_total: 2
  files_created: 10
  files_modified: 0
---

# Phase 01 Plan 01: Maven Project Skeleton and RocksDB Storage Summary

**One-liner:** Spring Boot 3.5.3 application with RocksDB 9.7.4 embedded storage using SmartLifecycle Integer.MIN_VALUE phase ordering and four column families (credentials, acl_rules, retained_messages, metadata).

## What Was Built

A greenfield Maven project at `lightweight/` that provides:
1. A Spring Boot 3.5.3 application skeleton with Netty 4.1.122.Final pin, Caffeine cache, Actuator, and Prometheus metrics
2. RocksDB embedded storage with four column families, SmartLifecycle-ordered startup/shutdown, and schema version tracking

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Maven project skeleton + Spring Boot application entry point | d47225c68 | pom.xml, TbmqLightweightApplication.java, StorageConfiguration.java, tbmq-lightweight.yml, logback-spring.xml, TbmqLightweightApplicationTest.java |
| 2 | RocksDB storage interface, implementation, and column family management | 59a13dab5 | RocksDbColumnFamily.java, RocksDbStorage.java, DefaultRocksDbStorage.java, RocksDbStorageTest.java, TbmqLightweightApplication.java (updated) |

## Verification Results

- `mvn compile -q`: PASSED
- `mvn test -q` (8 tests): PASSED
  - `TbmqLightweightApplicationTest` (2 tests): contextLoads + configBinding
  - `RocksDbStorageTest` (6 tests): put/get, delete, persistence across restarts, schema_version init, all 4 CFs, SmartLifecycle phase
- `grep SmartLifecycle lightweight/src/main/java/` returns `DefaultRocksDbStorage`
- `grep Integer.MIN_VALUE lightweight/src/main/java/` returns `DefaultRocksDbStorage.getPhase()`
- `grep schema_version lightweight/src/main/java/` returns initialization in `DefaultRocksDbStorage`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed running=false guard preventing schema_version initialization**
- **Found during:** Task 2 - GREEN phase
- **Issue:** `getHandle()` checks `if (!running)` but `running` was set to true AFTER calling `get(METADATA, "schema_version")` during `start()`. All 6 tests failed with `IllegalStateException: RocksDB is not running`.
- **Fix:** Moved `running = true` before the schema_version initialization block in `start()` so the public API guards pass during startup initialization.
- **Files modified:** `DefaultRocksDbStorage.java`
- **Commit:** 59a13dab5

**2. [Rule 2 - Missing functionality] Added @EnableAutoConfiguration to enable Spring Boot auto-config**
- **Found during:** Task 2 integration - running full test suite
- **Issue:** With `DefaultRocksDbStorage` requiring `MeterRegistry` via constructor injection, the `@SpringBootTest` context failed: "No qualifying bean of type 'io.micrometer.core.instrument.MeterRegistry' available". The original `@SpringBootConfiguration` (following TBMQ pattern) without `@EnableAutoConfiguration` did not trigger Spring Boot's actuator auto-configuration for `MeterRegistry`.
- **Fix:** Added `@EnableAutoConfiguration` to `TbmqLightweightApplication`. This is the correct approach for the lightweight project (unlike the parent TBMQ project which relies on its multi-module configuration setup).
- **Files modified:** `TbmqLightweightApplication.java`
- **Commit:** 59a13dab5

## Key Technical Decisions

1. **`@EnableAutoConfiguration` on main class**: Unlike the parent TBMQ project, the lightweight standalone project needs explicit `@EnableAutoConfiguration` for Spring Boot auto-configuration (MeterRegistry, CacheManager, etc.) to work correctly.

2. **Schema_version initialization ordering**: `running` flag set before schema init in `start()` — the guard on `getHandle()` is intended for runtime callers, not internal startup logic.

3. **Single RocksDB.open() call for all column families**: Anti-pattern avoided — all 4 column families + default are opened atomically in one `RocksDB.open()` call, preventing CF handle corruption.

4. **Column family handle close ordering**: All `ColumnFamilyHandle` objects are closed BEFORE `db.close()` (RocksDB v7+ requirement — no finalizers).

## Known Stubs

None — all functionality is fully wired and tested.

## Self-Check: PASSED
