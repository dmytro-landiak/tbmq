# Project Retrospective

*A living document updated after each milestone. Lessons feed forward into future planning.*

## Milestone: v1.0 — TBMQ Lightweight v1.0

**Shipped:** 2026-05-07
**Phases:** 7 | **Plans:** 21 | **Tasks:** 35

### What Was Built

A stripped-down, single-node MQTT broker that eliminates Kafka, PostgreSQL, and Redis/Valkey from the standard TBMQ. Ships as a single Docker image (`docker run -p 1883:1883`) with RocksDB embedded storage for credentials/ACLs and in-process `LinkedBlockingQueue` for message dispatch. Multi-arch (amd64 + arm64) verified on real AWS Graviton hardware.

- **Foundation (Phase 1)** — Spring Boot 3.5.3 + RocksDB 9.7.4 (4 column families) + Caffeine cache + Netty TCP bootstrap + Prometheus actuator + multi-arch Dockerfile (`eclipse-temurin:17-jre-jammy` glibc; Alpine hard-blocked due to RocksDB JNI on ARM64).
- **Core protocol MQTT 3.1.1 (Phase 2)** — Full QoS 0/1/2 handshakes, retained messages, LWT keyed by session UUID (correct takeover isolation), keep-alive with raw-socket test infra, client takeover.
- **Message dispatch (Phase 3)** — `MsgDispatcherService` behind `PublishMsgQueueFactory` (swap path to LMAX Disruptor preserved), wildcard subscription trie + retained-msg trie copied from TBMQ and trimmed, non-blocking Netty handoff via `queue.offer()`.
- **Security (Phase 4)** — RocksDB-backed credential + ACL services with BCrypt, anonymous-disabled-by-default, default `tbmq/tbmq` installer (`@EventListener(ApplicationReadyEvent)`), regex authz patterns, TLS bootstrap, mTLS X.509, BouncyCastle PEM loading.
- **WebSocket transport (Phase 5)** — `AbstractServerBootstrap` refactor, ws:// (8084) and wss:// (8085), `Sec-WebSocket-Protocol: mqtt` echo, WSS gated on TLS.
- **MQTT 5.0 (Phase 6)** — Version detection, version-aware ACKs, user properties forward, topic aliases (in & out), session expiry, message-expiry decrement on retained delivery, shared subscriptions with `Math.floorMod` round-robin, reason-code resolver.
- **Hardening + Docker release (Phase 7)** — Prometheus surface (received/delivered/dropped/auth-success/auth-failure counters, queue-depth gauge, RocksDB read latency timer), `StartupWarningService` (TLS / volume mount / retained), `SoakTest` with PARANOID ByteBuf leak detection (140 008 msgs / 0 leaks / heap stable in 1-min smoke run), `arm64-validate.sh`, multi-arch Dockerfile validated end-to-end on AWS Graviton.
- **R1 code review pass (post Phase 7)** — 10 surgical improvements landed (T1–T10b on branch `lightweight/v1`): cache `TbTypeActorId` per session (-10 allocations per inbound packet), `MqttSessionHandler` boilerplate collapse, centralized disconnect-reason mapping, logging discipline, `ProtocolViolationException`, retained-delivery message-expiry decrement (MQTT 5.0 [MQTT-3.3.2-6] compliance), takeover race fix in `processConnect` + sibling fix in `processDisconnect`. Test count rose 154 → 172 with 0 failures.

### What Worked

- **Bottom-up phase ordering.** Infrastructure → protocol → dispatch → security → transport → MQTT 5.0 → hardening. Nothing depended on something not yet built. Each phase shipped a complete, testable capability.
- **"Copy-from-TBMQ, don't rewrite" rule.** For files like `ConcurrentMapSubscriptionTrie` (24 unit tests) and `ConcurrentMapRetainMsgTrie`, we copied the parent TBMQ class and trimmed (StatsManager, Guava, persistence couplings) rather than reimplementing. Found a latent upstream bug along the way (`notStartingWith$()` against empty topic).
- **Quick-task safety net.** Three quick tasks (`260430-itz`, `260430-mwd`, `260430-myq`) fixed a regression, swept doc drift, and added missing license headers/log placeholders without disrupting any phase. Especially valuable: `260430-itz` discovered a Phase 7 CR-02 fix had silently broken three pre-existing dispatcher tests since it landed.
- **Honest `human_needed` verification frontmatter.** The discipline of marking SC-3 as `human_needed` (rather than auto-passing it) kept the milestone honest until real ARM64 hardware was available — the AWS Graviton run on 2026-05-07 closed it definitively.
- **Layered test strategy.** `mvn -f lightweight/pom.xml -o test` gave fast feedback (172 unit + integration tests in a single JVM fork via `reuseForks=true`), `SoakTest` gave duration-independent leak/heap evidence under PARANOID detection, and the AWS Graviton smoke test gave the real-hardware confidence the others couldn't.

### What Was Inefficient

- **Phase 7 was marked "complete" before code-review fixes.** Sequence: phase-complete commit → review report → 6 CR/WR fixes (some real defects: null-password auth bypass, ungraceful dispatcher shutdown, MQTT 5 ACK headers) → re-verify. Ideally "complete" should land after the code-review-fix cycle, not before.
- **Documentation drift accumulated unnoticed.** REQUIREMENTS.md left PROTO-08/09/10 + OPS-01 unticked even after they shipped; ROADMAP.md showed Phase 7 as `0/2` while plans were `[x]`; STATE.md status was inconsistent with the work done. The 2026-04-30 follow-up pass swept all of this in one quick task — but the gap had been there for weeks.
- **Stop-on-empty-queue regression latent for ~6 weeks.** The Phase 7 CR-02 fix silently broke dispatcher tests for ~6 weeks because there was no CI gate on the lightweight Maven project. The 2026-04-30 quick task only found it because someone re-ran the suite manually. (CI workflow was explicitly dropped for v1.0; user accepted the regression-catch risk.)
- **R1 audit framing of Item 9 was misleading.** STATUS.md (2026-04-30) flagged a "ClientActor displaced-actor leak" that was actually two unrelated concerns: a real QoS-state-cleanup gap (already closed by Phase 7 CR-01) and a false "actor needs explicit stop()" framing (rested on misreading the architecture — ClientActor is per-clientId, not per-session, and is reused across reconnects). Took the R1 review pass on `lightweight/v1` to make the architectural call.
- **24h soak became a phantom blocker.** Brief flagged a 24-hour soak as a release gate, but test correctness (ByteBuf leak detection + heap stability) is duration-independent. The 1-minute smoke run was always going to be the canonical evidence; closing SC-2 as OPTIONAL on 2026-04-30 took the pressure off without reducing rigor.

### Patterns Established

- **Status-update memory file (`project_v1_release_gates.md`)** — a single document tracking "what's NOT a v1.0 gate" with `Why:` and `How to apply:` reasoning for each deferral. Prevents future sessions from re-raising decided-against items (CI, perf floor, repo extraction, README, main-merge).
- **`status: human_needed` with explicit `result:` field per check** — Phase 7 VERIFICATION.md established the pattern of recording the human-verification outcome inline (e.g., `result: passed (2026-05-07 — AWS EC2 t4g.small Graviton...)`). This survived three milestones-worth of scrutiny and was used to close Phase 1 and Phase 6 retroactively as well.
- **Architectural verdict embedded next to the audit claim** — When closing Item 9 in STATUS.md, the resolution included the architectural reasoning (one ClientActor per clientId, reused across reconnects) so future readers don't re-derive it. Same template applied to deferred items in PROJECT.md Key Decisions.
- **Same Dockerfile end-to-end** — Buying back ARM64 test debt across phases: the single `lightweight/docker/Dockerfile` is what gets built and run in `arm64-validate.sh`, in CI smoke (when wired), and in production. SC-3 closing implicitly closed Phase 1's deferred OPS-03 ARM64 item too.

### Key Lessons

1. **`human_needed` deferrals don't expire — close them deliberately.** Phase 1's ARM64 deferral lingered through Phase 2-7 and only closed when SC-3 ran on real hardware. Track these explicitly and revisit at every milestone gate.
2. **"Complete" should follow code review, not precede it.** Phase 7's "complete" commit + 6 follow-up fixes is the wrong order. Workflow tightening: phase-complete event should be gated on the code-review-fix cycle being green, not just on "the planned work is done".
3. **Doc drift compounds silently.** A weekly (or per-phase) `gsd-tools audit-uat` + `audit-open` sweep is cheap; doing it ad-hoc means weeks of stale frontmatter accumulate. Worth automating into the per-phase complete cycle.
4. **Audit findings need an architectural-judgment pass.** STATUS.md's auditor flagged 11 items; 4-5 turned out to be either already-fixed, stale, or based on misreading. The R1 review pass made those calls in one shot. A standing "audit-finding triage" step before queueing fixes would save churn.
5. **Treat "30%-OFF" verification gates as gates, not as nice-to-haves.** The R1 review pass found two genuine defects (T9 retained-message-expiry, T10/T10b takeover race) that would have shipped quietly without a code-review pass. The pass took ~1 day; the defects would have caused real customer issues.
6. **Pin upstream versions explicitly.** Foundation decisions (Netty 4.1.x not 4.2; RocksDB 9.7.4 not 10.x; eclipse-temurin glibc not Alpine) all paid off — Netty 4.2 broke APIs, RocksDB 10.x was too fresh, Alpine on ARM64 hard-blocks RocksDB JNI. Pinning was free at decision-time and saved real pain.

### Cost Observations

- **Model mix:** Mostly Sonnet 4.6 / Opus 4.6 across phases; specific breakdown not tracked.
- **Sessions:** ~5 weeks elapsed time (2026-04-02 first planning commit → 2026-05-07 milestone close). 35 tasks across 21 plans; not all tasks needed a fresh session.
- **Notable:**
  - The R1 code review pass (T1–T10b + report) was disproportionately high-value per session — 10 surgical improvements + 2 real defect fixes in ~1 working day.
  - Multi-arch buildx + push under QEMU emulation took ~5 minutes wallclock (Maven dep download 109s + package 135s + push 44s) — much cheaper than expected, would happily run again per release.
  - Phase 7 had the longest tail (CR-01 → WR-04 fixes spread over weeks, then SC-3 hardware run another 3 weeks later). Future milestones should plan a deliberate "post-phase-7 closure" buffer rather than calling it complete prematurely.

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v1.0 | ~5 weeks | 7 | Established the GSD workflow for this project; introduced `project_v*_release_gates.md` memory pattern; established the `human_needed`-with-`result:` verification template. |

### Cumulative Quality

| Milestone | Tests | Coverage | Zero-Dep Additions |
|-----------|-------|----------|-------------------|
| v1.0 | 172 | high (no formal coverage gate) | Eliminated Kafka, PostgreSQL, Redis/Valkey from standard TBMQ — replaced with RocksDB embedded, Caffeine in-process cache, `LinkedBlockingQueue` in-process dispatch. |

### Top Lessons (Verified Across Milestones)

1. **Pin upstream versions explicitly at the foundation phase.** Verified within v1.0 — Netty 4.1.x pin, RocksDB 9.7.4 pin, glibc-only Docker base all paid off.
2. **Treat code-review passes as gates, not as nice-to-haves.** Verified within v1.0 — R1 review found real defects in shipped-but-not-yet-closed code.
3. **Doc-drift sweeps need to be scheduled, not opportunistic.** Verified within v1.0 — three planning files drifted out of sync over ~3 weeks until the 2026-04-30 quick task swept them.
