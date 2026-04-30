---
quick_id: 260430-myq
description: Phase 7 code cleanup — license header + SLF4J placeholder fix
status: complete
date: 2026-04-30
---

# Quick Task 260430-myq — Summary

## Outcome

Both Phase 7 anti-patterns flagged in `.planning/STATUS.md` items 8b and 11 are resolved with two atomic commits.

| Task | File | Commit |
|------|------|--------|
| T1 — License header | `lightweight/src/main/java/.../metrics/BrokerMetricsService.java` | `0d906f4e9` |
| T2 — SLF4J placeholder | `lightweight/src/test/java/.../soak/SoakTest.java` | `46143e19d` |

## Verification

- `mvn -o clean compile` — BUILD SUCCESS (T1 verified)
- `mvn -o test-compile` — BUILD SUCCESS (T2 verified)
- `grep '{:\.0f}' lightweight/src/test/java/.../soak/SoakTest.java` — no matches
- `head -16 lightweight/src/main/java/.../metrics/BrokerMetricsService.java` — matches the canonical header used by every other Lightweight production file

## Notes — STATUS.md correction (item 8a)

STATUS.md item 8 also lists `StartupWarningService.java:79` as "always emits the retained-in-memory warning so the `if (!warnings.isEmpty())` guard is dead code". This claim is **stale**. Commit `5d25fbf3d` (`fix(07): WR-04 separate R1 limitation notice from misconfiguration warning banner`) already moved the retained-in-memory line to `log.info(...)` at line 76, separate from the WARN banner emitted only when `!warnings.isEmpty()` at line 79. No fix needed; STATUS.md will be corrected in the wrap-up update.

## Out of scope

- Item 9 — `ClientActor.java:203-215` displaced-actor leak (architectural change, requires design judgment)
- Item 10 — `DefaultMsgDispatcherService.dispatch()` race window (explicitly accepted as production-acceptable in STATUS.md)
