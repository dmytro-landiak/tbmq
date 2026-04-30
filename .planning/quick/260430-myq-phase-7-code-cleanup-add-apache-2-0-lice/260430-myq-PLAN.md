---
quick_id: 260430-myq
description: Phase 7 code cleanup — license header + SLF4J placeholder fix
status: in_progress
created: 2026-04-30
---

# Quick Task 260430-myq: Phase 7 code cleanup

## Context

Two trivial defects flagged by `.planning/STATUS.md` (the brief-vs-implementation audit):

1. **STATUS.md item 11 / `07-VERIFICATION.md` Info finding:** `BrokerMetricsService.java` is missing the Apache 2.0 license header that all other production source files in the lightweight module carry.

2. **STATUS.md item 8b:** `SoakTest.java:238` uses a Python-style format specifier `{:.0f}` inside an SLF4J log statement. SLF4J only recognises `{}` placeholders — the `{:.0f}` is treated as literal text, the first `{}` placeholder consumes `baselineHeap` (an unrounded double) and the second argument is unused. Result: log line reads `Heap baseline established: {:.0f} bytes (123 MB)` with the byte count silently dropped.

Both are isolated, mechanical fixes with no behavioural risk.

## Tasks

### T1 — Add Apache 2.0 license header to BrokerMetricsService.java

**Files:** `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsService.java`

**Action:** Prepend the standard 15-line header used by every other Lightweight source file (e.g., `StartupWarningService.java`):
```
/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */
```

**Verify:**
- `head -1 lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsService.java` shows `/**`
- `head -16 lightweight/src/main/java/.../BrokerMetricsService.java` matches the header in `StartupWarningService.java`
- `cd lightweight && mvn -o compile -q` succeeds

**Done when:** Header present, file compiles, no other content changed.

### T2 — Fix Python-style SLF4J placeholder in SoakTest.java

**Files:** `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/soak/SoakTest.java`

**Action:** Replace lines 237–239:
```java
LoggerFactory.getLogger(SoakTest.class).info(
        "Heap baseline established: {:.0f} bytes ({} MB)",
        baselineHeap, (long) (baselineHeap / 1_048_576));
```
with:
```java
LoggerFactory.getLogger(SoakTest.class).info(
        "Heap baseline established: {} bytes ({} MB)",
        (long) baselineHeap, (long) (baselineHeap / 1_048_576));
```

The `(long)` cast on `baselineHeap` discards fractional bytes, matching the original `{:.0f}` intent.

**Verify:**
- File compiles: `cd lightweight && mvn -o test-compile -q`
- `grep -n '{:\.0f}' lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/soak/SoakTest.java` returns no results

**Done when:** Placeholder uses SLF4J `{}` consistently, test compiles.

## Must-haves

- T1: license header added; file still compiles
- T2: `{:.0f}` removed from SoakTest.java; second argument now consumed by the second placeholder
- Two atomic commits with `chore:` (T1) and `fix:` (T2) Conventional Commit prefixes
- No other code touched

## Out of scope

- ClientActor displaced-actor leak (STATUS.md item 9) — architectural change
- DefaultMsgDispatcherService.dispatch() race window (item 10) — accepted as production-acceptable
- StartupWarningService dead-code-guard claim in STATUS.md item 8a — already resolved by WR-04 (`5d25fbf3d`); STATUS.md description is stale
