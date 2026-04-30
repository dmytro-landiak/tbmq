---
quick_id: 260430-mwd
description: Sweep documentation drift in REQUIREMENTS.md and ROADMAP.md
status: in_progress
created: 2026-04-30
---

# Quick Task 260430-mwd: Documentation drift sweep

## Context

Identified by `.planning/STATUS.md` (the brief-vs-implementation audit committed as `344199488`). Two planning files have status drift relative to the actual codebase state:

1. **REQUIREMENTS.md:** PROTO-08, PROTO-09, PROTO-10 (MQTT 5.0 features — Phase 6) and OPS-01 (Prometheus metrics — Phase 7) still show `[ ]` in the v1 Requirements checklist (lines 19–21, 49) and `Pending` in the Traceability table (lines 124–127). All four are SATISFIED per `.planning/phases/06-mqtt-5/06-VERIFICATION.md` and `.planning/phases/07-hardening-docker-release/07-VERIFICATION.md`. PROJECT.md treats milestone v1.0 as done (commit `20c14fc06`).

2. **ROADMAP.md:** The "Progress" table at line 137 shows Phase 7 as `0/2 In Progress` with completion date `-`, but the same file lists plans `07-01-PLAN.md` and `07-02-PLAN.md` as `[x]` (lines 121–122), and the Phase 7 completion commit `b5a2d85e3` lands before the milestone marker `20c14fc06`. Phase 7 actually completed 2026-04-12 (date of `b5a2d85e3`).

Pure documentation sync. No code changes. No verification needed beyond visual diff.

## Tasks

### T1 — Update REQUIREMENTS.md

**Files:** `.planning/REQUIREMENTS.md`

**Action:**
- Line 19 (PROTO-08): change `[ ]` → `[x]`
- Line 20 (PROTO-09): change `[ ]` → `[x]`
- Line 21 (PROTO-10): change `[ ]` → `[x]`
- Line 49 (OPS-01): change `[ ]` → `[x]`
- Lines 124–127 (Traceability rows for PROTO-08, PROTO-09, PROTO-10, OPS-01): change `Pending` → `Complete`

**Verify:** `grep -c '\- \[ \]' .planning/REQUIREMENTS.md` returns 0 for v1 section; `grep -c 'Pending' .planning/REQUIREMENTS.md` returns 0 in the Traceability table.

**Done when:** All four requirement IDs ticked, all four Traceability rows show `Complete`.

### T2 — Update ROADMAP.md

**Files:** `.planning/ROADMAP.md`

**Action:**
- Line 137 (Phase 7 row): change `| 7. Hardening and Docker Release | 0/2 | In Progress | - |` → `| 7. Hardening and Docker Release | 2/2 | Complete | 2026-04-12 |`

**Verify:** `grep 'Phase 7\|Hardening and Docker' .planning/ROADMAP.md | tail -1` shows `2/2 | Complete | 2026-04-12`.

**Done when:** Progress table reflects Phase 7 completion consistent with the rest of ROADMAP.md and PROJECT.md.

## Must-haves

- All four MQTT 5.0/Ops requirement IDs (PROTO-08, PROTO-09, PROTO-10, OPS-01) marked `[x]` in REQUIREMENTS.md checklist
- Same four entries show `Complete` in REQUIREMENTS.md Traceability table
- ROADMAP.md Progress table Phase 7 row shows `2/2 Complete` with date `2026-04-12`
- Single atomic commit with `docs:` Conventional Commit prefix

## Out of scope

- STATE.md `status: verifying` field — keep `verifying` until Phase 7 SC-3/SC-4 are signed off (see STATUS.md item 6)
- Repository extraction decision (separate repo vs in-tree sibling) — needs user decision
- README.md and CI workflow additions — needs user decision
