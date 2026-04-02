# Phase 1: Foundation - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-02
**Phase:** 01-foundation
**Mode:** Auto (all decisions auto-selected with recommended defaults)
**Areas discussed:** Project structure, RocksDB schema design, Netty bootstrap configuration, Docker and deployment conventions

---

## Project Structure

| Option | Description | Selected |
|--------|-------------|----------|
| Flat modular | Single root POM with package-level separation | ✓ |
| Multi-module (TBMQ-style) | Deep hierarchy mirroring existing TBMQ | |
| Monolithic single module | All code in one Maven module | |

**User's choice:** [auto] Flat modular (recommended default)
**Notes:** Simpler than TBMQ's deep multi-module hierarchy; appropriate for a focused lightweight broker. Copy-first strategy — no shared library extraction in R1.

---

## RocksDB Schema Design

| Option | Description | Selected |
|--------|-------------|----------|
| One CF per entity type + metadata | Separate column families with schema version tracking | ✓ |
| Single default CF | All data in one column family with key prefixes | |
| Separate RocksDB instances | One DB per entity type | |

**User's choice:** [auto] One CF per entity type + metadata (recommended default)
**Notes:** Forward-compatible with R2 persistent sessions. JSON-serialized values for debuggability in R1. Explicit `close()` on all RocksDB objects required (JNI memory safety).

---

## Netty Bootstrap Configuration

| Option | Description | Selected |
|--------|-------------|----------|
| Boss/worker with Epoll on Linux | 1 boss + CPU-core workers, native transport | ✓ |
| NIO only | Cross-platform NIO, no native transport | |
| Virtual threads (Java 21) | Loom-based, no event loop | |

**User's choice:** [auto] Boss/worker with Epoll on Linux (recommended default)
**Notes:** Aligns with existing TBMQ Netty patterns. NIO fallback on non-Linux platforms. Minimal Phase 1 pipeline (no MQTT codec yet).

---

## Docker and Deployment

| Option | Description | Selected |
|--------|-------------|----------|
| /data as root persistent path | eclipse-temurin:17-jre-jammy, /data/rocksdb, TBMQ_ env prefix | ✓ |
| /var/lib/tbmq path convention | Follows Linux FHS standard | |
| Custom configurable path | User sets path via env var, no default | |

**User's choice:** [auto] /data as root persistent path (recommended default)
**Notes:** `/data/rocksdb` matches Docker conventions. Alpine base explicitly excluded (RocksDB JNI musl incompatibility). Multi-stage Docker build.

---

## Claude's Discretion

- Exact Maven artifact IDs and module naming
- Spring Boot configuration property naming (TBMQ_ prefix convention followed)
- Specific Netty channel handler class names and package layout
- Prometheus metric names (Micrometer conventions)
- Dockerfile COPY ordering and layer optimization

## Deferred Ideas

None — discussion stayed within phase scope
