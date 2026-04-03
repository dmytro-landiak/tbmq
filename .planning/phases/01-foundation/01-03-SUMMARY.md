---
phase: 01-foundation
plan: "03"
subsystem: infra
tags: [docker, multi-stage, eclipse-temurin, rocksdb, healthcheck, multi-arch, prometheus, netty]
dependency_graph:
  requires: [01-01, 01-02]
  provides: [docker-image, container-runtime, volume-mount-rocksdb, healthcheck, multi-arch-build]
  affects: [deployment, ops, phase-2, phase-7]
tech_stack:
  added:
    - eclipse-temurin:17-jre-jammy (Docker runtime base — glibc, NOT alpine; hard-blocked by RocksDB JNI on ARM64)
    - maven:3.9-eclipse-temurin-17 (Docker build stage; -jammy suffix tag does not exist)
    - curl (apt-get install into runtime stage; required for HEALTHCHECK)
  patterns:
    - Multi-stage Docker build for minimal runtime image (Maven build + JRE runtime)
    - Build context is lightweight/ directory; Dockerfile at lightweight/docker/Dockerfile
    - VOLUME instruction for RocksDB persistence across restarts
    - HEALTHCHECK via Spring Boot Actuator curl (--start-period=15s for JVM+RocksDB init)
    - JVM container-aware flags (UseContainerSupport, MaxRAMPercentage=75.0)
    - Netty leak detection disabled at production runtime (-Dio.netty.leakDetection.level=DISABLED)
key_files:
  created:
    - lightweight/docker/Dockerfile
    - lightweight/.dockerignore
  modified: []
key-decisions:
  - "Use maven:3.9-eclipse-temurin-17 (not -jammy variant) for build stage — the -jammy suffix tag does not exist for Maven+JDK17; build stage OS irrelevant since only JAR is copied"
  - "Runtime base MUST be eclipse-temurin:17-jre-jammy (glibc) — Alpine/musl is hard-blocked by RocksDB JNI on ARM64 (UnsatisfiedLinkError)"
  - "MaxRAMPercentage=75.0 reserves 25% for RocksDB JNI off-heap and Netty direct buffers"
  - "HEALTHCHECK --start-period=15s allows JVM and RocksDB initialization before Docker marks container unhealthy"
requirements-completed: [OPS-02, OPS-03]
metrics:
  duration: "~15 min (Task 1 build + Task 2 human verification)"
  completed: "2026-04-03"
  tasks_completed: 2
  tasks_total: 2
  files_created: 2
  files_modified: 0
status: complete
---

# Phase 01 Plan 03: Docker Image Summary

**Multi-stage Dockerfile packaging tbmq-lightweight into eclipse-temurin:17-jre-jammy with HEALTHCHECK, VOLUME for RocksDB persistence, and human-verified end-to-end container operation (TCP on 1883, health UP, Prometheus metrics, graceful shutdown)**

## Performance

- **Duration:** ~15 min (Docker build in Task 1 + human verification in Task 2)
- **Started:** 2026-04-03T11:55:27Z
- **Completed:** 2026-04-03 (human approval of Task 2 checkpoint)
- **Tasks:** 2 (1 auto + 1 human-verify)
- **Files created:** 2

## Accomplishments

- Created multi-stage Dockerfile with Maven build stage (`maven:3.9-eclipse-temurin-17`) and eclipse-temurin:17-jre-jammy runtime stage; image builds successfully (369MB)
- Dockerfile includes HEALTHCHECK via `actuator/health` (curl), VOLUME at `/data/rocksdb`, EXPOSE 1883/8083, and JVM container flags (UseContainerSupport, MaxRAMPercentage=75.0, Netty leak detection disabled)
- Human verified all acceptance criteria: container starts and accepts TCP on 1883, health returns UP, Prometheus shows `mqtt_connections_active` and JVM metrics, graceful shutdown produces no errors, RocksDB data persists across container restart with same volume mount

## Task Commits

Each task was committed atomically:

1. **Task 1: Multi-stage Dockerfile and .dockerignore** - `b434ef6ca` (feat)
2. **Task 2: Verify Docker container starts and accepts connections** - Human-approved checkpoint; no code commit (verification-only task)

**Plan metadata:** (this docs commit)

## Files Created/Modified

- `lightweight/docker/Dockerfile` - Multi-stage Docker build: Maven build stage + eclipse-temurin:17-jre-jammy runtime with HEALTHCHECK, VOLUME /data/rocksdb, EXPOSE 1883/8083, and JVM container flags
- `lightweight/.dockerignore` - Excludes `.planning/`, `.git/`, `target/`, `*.md`, `.idea/`, `*.iml`, `docker/` from build context

## Decisions Made

- Used `maven:3.9-eclipse-temurin-17` (not `-jammy`) for build stage — the `-jammy` suffix tag does not exist for this Maven+JDK combination; build stage OS is irrelevant since only the fat JAR is copied to runtime
- Runtime base is `eclipse-temurin:17-jre-jammy` (glibc/Ubuntu 22.04) — Alpine/musl is hard-blocked by RocksDB JNI incompatibility (ARM64 UnsatisfiedLinkError); this constraint is documented in the Dockerfile with a warning comment
- `HEALTHCHECK --start-period=15s` selected to allow JVM startup and RocksDB initialization before Docker health probes fire
- `MaxRAMPercentage=75.0` reserves 25% headroom for RocksDB JNI off-heap memory and Netty direct buffers

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed non-existent Docker image tag `maven:3.9-eclipse-temurin-17-jammy`**
- **Found during:** Task 1 — first `docker build` attempt
- **Issue:** The plan specified `FROM maven:3.9-eclipse-temurin-17-jammy AS build` but this image tag does not exist on Docker Hub. The `-jammy` suffix is not published for the Maven + eclipse-temurin-17 combination.
- **Fix:** Changed build stage to `FROM maven:3.9-eclipse-temurin-17` (Ubuntu 24.04 Noble, glibc). The build stage OS is irrelevant since only `target/tbmq-lightweight.jar` is copied to the runtime stage. The runtime stage correctly uses `eclipse-temurin:17-jre-jammy` (Ubuntu 22.04 Jammy).
- **Files modified:** `lightweight/docker/Dockerfile`
- **Committed in:** b434ef6ca (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 bug — wrong Docker tag)
**Impact on plan:** Fix was required for build to succeed. No scope creep, runtime image unchanged.

## Issues Encountered

- Maven Docker tag `maven:3.9-eclipse-temurin-17-jammy` does not exist (only `maven:3.9-eclipse-temurin-17` is available). Fixed inline during Task 1.

## User Setup Required

None - no external service configuration required. The Docker image runs with zero external dependencies.

## Next Phase Readiness

- Phase 1 Foundation is fully complete: Spring Boot skeleton (01-01), Netty TCP + Prometheus metrics (01-02), and Docker image with human verification (01-03)
- Phase 2 (Core Protocol MQTT 3.1.1) can begin; this Docker image can be used for integration testing with MQTT clients
- Multi-arch buildx (`linux/amd64,linux/arm64`) is wired but requires `--push` to a registry; ARM64 hardware validation deferred to Phase 7
- No blockers for Phase 2

## Known Stubs

None — all deliverables are fully functional and human-verified.

## Self-Check: PASSED

- `lightweight/docker/Dockerfile` — FOUND
- `lightweight/.dockerignore` — FOUND
- Commit b434ef6ca — FOUND in git log
- Task 2 human verification — APPROVED by user
