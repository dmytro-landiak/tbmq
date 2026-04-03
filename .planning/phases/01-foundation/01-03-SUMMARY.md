---
phase: 01-foundation
plan: "03"
subsystem: docker-packaging
tags: [docker, multi-stage, eclipse-temurin, rocksdb, healthcheck, multi-arch, checkpoint]
dependency_graph:
  requires: [01-01, 01-02]
  provides: [docker-image, container-runtime, volume-mount-rocksdb]
  affects: [deployment, ops]
tech_stack:
  added:
    - eclipse-temurin:17-jre-jammy (Docker runtime base — glibc, NOT alpine)
    - maven:3.9-eclipse-temurin-17 (Docker build stage)
  patterns:
    - Multi-stage Docker build for minimal runtime image
    - VOLUME instruction for RocksDB persistence across restarts
    - HEALTHCHECK via Spring Boot Actuator curl
    - JVM container-aware flags (UseContainerSupport, MaxRAMPercentage)
key_files:
  created:
    - lightweight/docker/Dockerfile
    - lightweight/.dockerignore
  modified: []
decisions:
  - Use maven:3.9-eclipse-temurin-17 (not -jammy variant) for build stage — the -jammy suffix tag does not exist for this Maven+JDK combination; build stage OS does not matter since only the JAR is copied to runtime
metrics:
  duration: "4 minutes"
  completed: "2026-04-03"
  tasks_completed: 1
  tasks_total: 2
  files_created: 2
  files_modified: 0
status: checkpoint-reached
---

# Phase 01 Plan 03: Docker Image Summary (Partial — Checkpoint at Task 2)

**One-liner:** Multi-stage Dockerfile using maven:3.9-eclipse-temurin-17 build stage and eclipse-temurin:17-jre-jammy runtime with HEALTHCHECK, VOLUME /data/rocksdb, port exposures 1883/8083, and container-aware JVM flags.

## What Was Built

Task 1 complete — the Docker packaging layer for TBMQ Lightweight:

1. **`lightweight/docker/Dockerfile`** — Multi-stage build producing `thingsboard/tbmq-lightweight` image:
   - Build stage: `maven:3.9-eclipse-temurin-17` (Ubuntu Noble with glibc, Maven 3.9, JDK 17)
   - Runtime stage: `eclipse-temurin:17-jre-jammy` (Ubuntu 22.04 Jammy, glibc — not Alpine, RocksDB JNI incompatible with musl)
   - Dependency layer caching: `COPY pom.xml` then `mvn dependency:go-offline` before copying source
   - `VOLUME ["/data/rocksdb"]` for RocksDB persistence across container restarts (D-12, OPS-04)
   - `EXPOSE 1883 8083` for MQTT TCP and HTTP/Actuator
   - `HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3` via `curl -sf http://localhost:8083/actuator/health` (D-14)
   - JVM ENTRYPOINT: `-XX:+UseContainerSupport`, `-XX:MaxRAMPercentage=75.0`, `-Dio.netty.leakDetection.level=DISABLED`

2. **`lightweight/.dockerignore`** — Excludes `.planning`, `.git`, `*.md`, `target/`, `docker/`, `.idea`, `*.iml`

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Multi-stage Dockerfile and .dockerignore | b434ef6ca | lightweight/docker/Dockerfile, lightweight/.dockerignore |
| 2 | Verify Docker container starts and accepts connections | PENDING — checkpoint | Manual verification required |

## Verification Results

- `docker build -f docker/Dockerfile -t thingsboard/tbmq-lightweight:test .` — PASSED
- Image size: 369MB (eclipse-temurin JRE + curl + tbmq-lightweight.jar)
- All acceptance criteria verified:
  - `FROM maven:3.9-eclipse-temurin-17 AS build` present
  - `FROM eclipse-temurin:17-jre-jammy` present
  - Alpine warning comment present
  - `VOLUME ["/data/rocksdb"]` present
  - `EXPOSE 1883 8083` present
  - `HEALTHCHECK` with `actuator/health` present
  - `-XX:MaxRAMPercentage=75.0` present
  - `-Dio.netty.leakDetection.level=DISABLED` present
  - `tbmq-lightweight.jar` present
  - `.dockerignore` contains `.planning` and `target/`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed non-existent Docker image tag `maven:3.9-eclipse-temurin-17-jammy`**
- **Found during:** Task 1 — first `docker build` attempt
- **Issue:** The plan specified `FROM maven:3.9-eclipse-temurin-17-jammy AS build` but this image tag does not exist on Docker Hub. The `-jammy` suffix is not published for the Maven + eclipse-temurin-17 combination.
- **Fix:** Changed build stage to `FROM maven:3.9-eclipse-temurin-17` (Ubuntu 24.04 Noble, glibc). The build stage OS is irrelevant since only `target/tbmq-lightweight.jar` is copied to the runtime stage. The runtime stage correctly uses `eclipse-temurin:17-jre-jammy` (Ubuntu 22.04 Jammy) which satisfies D-11.
- **Files modified:** `lightweight/docker/Dockerfile`
- **Commit:** b434ef6ca

## Checkpoint: Task 2 — Human Verification

Task 2 is a `checkpoint:human-verify` gate requiring manual verification of the complete broker behavior inside Docker. See checkpoint details in the plan return message.

## Known Stubs

None — Dockerfile is fully functional. Task 2 verification is pending human confirmation.

## Self-Check: PASSED (Task 1)

- `lightweight/docker/Dockerfile` — FOUND
- `lightweight/.dockerignore` — FOUND
- Commit b434ef6ca — FOUND in git log
