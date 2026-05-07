# Roadmap: TBMQ Lightweight

## Milestones

- ✅ **v1.0 — TBMQ Lightweight v1.0** — Phases 1-7 (shipped 2026-05-07)
- 📋 **v1.1 / R2** (planned — run `/gsd-new-milestone` to start)

## Phases

<details>
<summary>✅ v1.0 TBMQ Lightweight v1.0 (Phases 1-7) — SHIPPED 2026-05-07</summary>

- [x] Phase 1: Foundation (3/3 plans) — completed 2026-04-03
- [x] Phase 2: Core Protocol (MQTT 3.1.1) (5/5 plans) — completed 2026-04-07
- [x] Phase 3: Message Dispatch (3/3 plans) — completed 2026-04-08
- [x] Phase 4: Security (3/3 plans) — completed 2026-04-09
- [x] Phase 5: WebSocket Transport (2/2 plans) — completed 2026-04-09
- [x] Phase 6: MQTT 5.0 (3/3 plans) — completed 2026-04-11
- [x] Phase 7: Hardening and Docker Release (2/2 plans) — completed 2026-04-12; SC-3 ARM64 hardware verified 2026-05-07 on AWS Graviton

Full details: [.planning/milestones/v1.0-ROADMAP.md](milestones/v1.0-ROADMAP.md)

</details>

### 📋 v1.1 / R2 (Planned)

No phases yet. Likely R2 scope (from PROJECT.md "Out of Scope — deferred to R2"):

- Persistent sessions / offline QoS 1/2 message queuing (PERS-01)
- Durable subscriptions across broker restarts (PERS-02)
- Persistent retained messages across restarts (PERS-03 — column family `RETAINED_MESSAGES` already reserved in `RocksDbColumnFamily` enum)
- RocksDB schema-version / migration framework (`metadata` column family with schema version key — pre-R2 hygiene flagged in STATE.md Blockers/Concerns)

Run `/gsd-new-milestone` to confirm scope and generate REQUIREMENTS.md + ROADMAP details for R2.

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
| ----- | --------- | -------------- | ------ | --------- |
| 1. Foundation | v1.0 | 3/3 | Complete | 2026-04-03 |
| 2. Core Protocol (MQTT 3.1.1) | v1.0 | 5/5 | Complete | 2026-04-07 |
| 3. Message Dispatch | v1.0 | 3/3 | Complete | 2026-04-08 |
| 4. Security | v1.0 | 3/3 | Complete | 2026-04-09 |
| 5. WebSocket Transport | v1.0 | 2/2 | Complete | 2026-04-09 |
| 6. MQTT 5.0 | v1.0 | 3/3 | Complete | 2026-04-11 |
| 7. Hardening and Docker Release | v1.0 | 2/2 | Complete | 2026-04-12 (SC-3 verified 2026-05-07) |
