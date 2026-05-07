---
generated: 2026-04-30
generator: brief-vs-implementation audit (read-only)
brief_source: tbmq-lightweight-brief.md
project_planning: .planning/
codebase_under_audit: lightweight/
last_followup_pass: 2026-05-07
---

# TBMQ Lightweight — Status

## 0. Follow-up pass (2026-04-30)

Sweep of every actionable item that didn't require human judgment, hardware, credentials, or external services.

### Done — 5 items

| # | Item | Commit | Notes |
|---|------|--------|-------|
| 1 | REQUIREMENTS.md drift (PROTO-08/09/10, OPS-01 ticked + Traceability rows → Complete) | `4c039b49e` | Quick task `260430-mwd` |
| 2 | ROADMAP.md drift (Phase 7 row → `2/2 Complete (2026-04-12)`; "Phases" header line ticked; plan-count line normalised) | `4c039b49e` | Same quick task as item 1 |
| 3 | `BrokerMetricsService.java` Apache 2.0 license header added | `0d906f4e9` | Quick task `260430-myq` (T1) — `mvn -o clean compile` BUILD SUCCESS |
| 4 | `SoakTest.java:238` Python-style `{:.0f}` → SLF4J `{}` with `(long)` cast | `46143e19d` | Quick task `260430-myq` (T2) — `mvn -o test-compile` BUILD SUCCESS |
| 5 | Item 10 — `DefaultMsgDispatcherService` `dispatch()`/`stop()` race window: drain orphaned queue messages on shutdown so silent loss becomes counted loss on `mqtt.dispatch.dropped.total` | `0029a4377` (fix), `7f647074f` (spec+plan) | TDD via brainstorming → writing-plans → executing-plans; design at `docs/superpowers/specs/2026-04-30-dispatcher-race-window-design.md`; `mvn -f lightweight/pom.xml -o test` BUILD SUCCESS (154/154 pass) |

### Stale finding corrected — 1 item

- **Item 8a** (`StartupWarningService.java:79` "always emits the retained-in-memory warning so the `if (!warnings.isEmpty())` guard is dead code") is **stale**. Commit `5d25fbf3d` (WR-04, "separate R1 limitation notice from misconfiguration warning banner") moved the retained-in-memory line to `log.info(...)` at line 76, distinct from the WARN banner that is correctly guarded at line 79. No code change needed.

### User-confirmed since the autonomous pass — 2 items (2026-04-30)

- **SC-4 docker-logs banner visual check — DONE.** User confirmed the `STARTUP WARNINGS` banner is visible in real `docker logs` output. The Phase 7 success criterion 4 is signed off.
- **SC-2 24-hour soak — CLOSED as OPTIONAL.** User decision: drop the 24-hour soak as a release gate. Rationale aligns with the original audit note ("Not strictly blocking — test correctness is duration-independent"). The 1-minute smoke run on 2026-04-12 (140 008 messages, 0 ByteBuf leaks under PARANOID, heap stable) is the canonical Phase 7 SC-2 evidence.

### User-decided / confirmed since the autonomous pass — 6 items (2026-05-07)

All previously-skipped items now resolved or decided:

- **SC-3 ARM64 hardware run — DONE.** Validated on AWS EC2 t4g.small Graviton (Ubuntu 26.04 ARM64) using `dlandiak2110/tbmq-lightweight:latest`. All four canonical SC-3 checks confirmed: arm64/linux via `docker inspect`; broker logged `Started TbmqLightweightApplication` with no `UnsatisfiedLinkError` (RocksDB JNI on glibc/aarch64 OK); mosquitto pub/sub round-trip on port 1883; default `tbmq/tbmq` credentials persisted across container restart with named volume.
- **STATE.md `status: verifying` → `complete` — DONE.** Flipped after SC-3 passed; all four Phase 7 success criteria are now signed off (SC-1 ✓, SC-2 OPTIONAL/smoke-only, SC-3 ✓, SC-4 ✓).
- **Multi-arch Docker push (test registry) — DONE.** Image built via `docker buildx --platform linux/amd64,linux/arm64` and pushed to `dlandiak2110/tbmq-lightweight:1.0.0` and `:latest`. OCI manifest index `sha256:98554b72...` contains both `linux/amd64` (`sha256:0a55907c...`) and `linux/arm64` (`sha256:76a61528...`). Production push to `thingsboard/tbmq-lightweight` org would need separate Docker Hub credentials and is not pursued here.
- **Repository extraction — DEFERRED through R2.** User decision: keep `lightweight/` as in-tree sibling project alongside the main TBMQ codebase in this repo for the duration of R1 + R2; revisit the repo-split question only after R2 ships. PROJECT.md "Key Decisions" row updated 2026-05-07: Pending → "Deferred — `lightweight/` stays as in-tree sibling project through R1 + R2; revisit post-R2".
- **`lightweight/README.md` — DEFERRED until after R2.** User decision: paired with the eventual repo-split decision; hold for the post-R2 ship.
- **CI workflow `mvn -f lightweight/pom.xml verify` on PRs — DROPPED.** User decision: not pursuing for v1.0. New-risk #15 (regression-catch motivation) is acknowledged but accepted; not a v1.0 gate.

Also done 2026-05-07: branch rename `codebase-map` → `lightweight/v1` (pushed to origin; old branch deleted on remote). Performance-floor blocker (10 k connections / 50 k msg/s) removed from STATE.md — no explicit throughput floor required for v1.0.

### Skipped — 0 items remaining

All six previously-skipped items have been resolved or decided. See "User-decided / confirmed since the autonomous pass — 6 items (2026-05-07)" above.

### What's left for the user

**Pre-release gates** (block public 1.0.0 tag):
1. ~~Run `scripts/arm64-validate.sh` on real ARM64 hardware (SC-3) — the only remaining Phase 7 success criterion~~ — **Done 2026-05-07** (AWS EC2 t4g.small Graviton; full evidence in `07-VERIFICATION.md` and `07-HUMAN-UAT.md`).

**Product/tooling decisions** (all resolved 2026-05-07 — none block v1.0):
2. ~~Decide repository strategy~~ — **Deferred through R2** (in-tree sibling project; revisit post-R2).
3. ~~Authorise / produce `lightweight/README.md`~~ — **Deferred until after R2**.
4. ~~Authorise / wire CI workflow for the lightweight Maven project~~ — **Dropped for v1.0**.
5. ~~Provide Docker Hub credentials and authorise `docker buildx push` for `1.0.0` and `:latest`~~ — **Done 2026-05-07** (test registry `dlandiak2110/`; production `thingsboard/` push deferred — not a v1.0 gate).

**Optional cleanup** (post-1.0.0):
6. ~~Item 9 (ClientActor displaced-actor leak) — needs an explicit `stop()` design~~ — **Resolved 2026-05-04** during R1 code review pass on `lightweight/v1`. The "QoS state leak" half was already closed by Phase 7 CR-01 (`6dc0166e0`); the "explicit stop()" half rested on a misreading of the architecture (ClientActor is keyed by clientId and reused across reconnects — there is no displaced actor object, only a displaced ClientSessionCtx whose heavy state is cleared and whose reference falls out of scope). The R1 review surveyed `ClientActor` under T5 and rejected further changes; T11 (TbActorMailbox concurrency) was also rejected as sound. The real takeover defect was a separate timing race fixed by **T10** (`9da6ee54f`) + **T10b** (`5ddc867b1`). Verdict from `lightweight/REVIEW_REPORT.md`: existing pattern is sound.
7. ~~Item 10 (dispatch race window) — production-acceptable, only revisit if benchmarks show a problem~~ — **Done 2026-04-30** (`0029a4377`)

---

## 1. Executive summary

All 11 R1 features from the brief are implemented and pass automated tests. PROJECT.md, ROADMAP.md, and the Phase 7 completion commit (`20c14fc06`) treat milestone v1.0 as done, but the project is not yet at "shipped": Phase 7's HUMAN-UAT for ARM64-on-real-hardware and the docker-logs warning banner is still pending, the 24-hour soak run was only smoke-tested at 1 minute, and several status fields drifted out of sync (REQUIREMENTS.md still flags PROTO-08/09/10 and OPS-01 as `[ ]`; STATE.md status is `verifying`; ROADMAP.md progress table shows Phase 7 as 0/2 even though both plans are `[x]`). The repository-strategy decision in the brief (separate repo) was implemented as a sibling Maven project under `lightweight/` that is *not* part of the parent reactor — close to Option A but not a separate Git repo.

## 2. Job done so far

Phases run bottom-up: infrastructure → MQTT 3.1.1 → dispatch → security → WS → MQTT 5.0 → hardening. Every phase has a VERIFICATION.md, an automated UAT.md, and (where applicable) a HUMAN-UAT.md. All seven `complete phase execution` commits exist on the current branch.

| # | Phase | Status | Plans | Key artifacts | Commits | Modules touched |
|---|-------|--------|-------|---------------|---------|-----------------|
| 1 | Foundation | complete (1 deferred item) | 3/3 | Spring Boot app, RocksDB JNI 9.7.4 (4 column families), Caffeine cache, Netty TCP bootstrap, Prometheus actuator, multi-arch Dockerfile | `b03083edc` (phase complete), Dockerfile, `01-VERIFICATION.md` | `lightweight/storage`, `lightweight/cache`, `lightweight/server`, `lightweight/metrics`, `lightweight/docker` |
| 2 | Core Protocol (MQTT 3.1.1) | complete | 5/5 | Actor system port, MQTT pipeline, CONNECT/CONNACK, keep-alive, QoS 0/1/2, SUBSCRIBE/UNSUBSCRIBE, retained messages, LWT, client takeover | `bff25561d` (phase complete) | `lightweight/actors`, `lightweight/server`, `lightweight/service/mqtt/{retain,will}`, `lightweight/service/subscription` |
| 3 | Message Dispatch | complete | 3/3 | Subscription trie + retained-msg trie (copied/trimmed from TBMQ), `MsgDispatcherService` behind a queue factory, `LinkedBlockingQueue` impl, `$SYS/` exclusion, wildcard fan-out | `464f10041` (phase complete) | `lightweight/service/{dispatch,subscription,mqtt/retain}`, `lightweight/common` |
| 4 | Security | complete (2 deferred to docker smoke) | 3/3 | RocksDB-backed credential + ACL services with Caffeine cache, BCrypt, anonymous-disabled-by-default, default tbmq/tbmq installer, regex authz patterns, TLS bootstrap, mTLS X.509 auth, PEM loading via BouncyCastle | `951c15b91` (phase complete) | `lightweight/security/{auth,acl}`, `lightweight/ssl`, `lightweight/server/tls`, `lightweight/install`, `lightweight/config` |
| 5 | WebSocket Transport | complete | 2/2 | `AbstractServerBootstrap` refactor, `MqttSessionHandlerFactory`, ws:// (8084) and wss:// (8085) bootstraps, four WS frame handlers, subprotocol echo (`mqttv3.1,mqtt`), WSS gated on TLS | `5ced2d580` (phase complete) | `lightweight/server/{ws,wss,wshandler}`, `lightweight/config` |
| 6 | MQTT 5.0 | complete (2 human items also passed) | 3/3 | Version detection on CONNECT, version-aware CONNACK/PUBACK/PUBREC/SUBACK/DISCONNECT, user properties forward, topic aliases (in & out), session expiry, message expiry on retained, shared subscriptions with `Math.floorMod` round-robin, reason-code resolver | `8d44c9394` (phase complete), 27 new integration tests | `lightweight/util/{MqttPropertiesUtil,MqttReasonCodeResolver}`, `lightweight/session/TopicAliasCtx`, `lightweight/service/{mqtt,dispatch,mqtt/retain}` |
| 7 | Hardening & Docker Release | complete-pending-human | 2/2 | Prometheus metrics surface (received/delivered/dropped/auth-success/auth-failure counters, queue-depth gauge, RocksDB read latency), `StartupWarningService` (TLS / volume mount / retained), `SoakTest` (PARANOID leak detection, 500 clients, heap-stability assertion), `scripts/arm64-validate.sh` | `b5a2d85e3` (phase complete), `20c14fc06` (milestone v1.0 marker), 6 code-review fixes (`5d25fbf3d`, `1201e82d7`, `153648eca`, `b3e1f434e`, `662fd2405`, `6dc0166e0`) | `lightweight/metrics`, `lightweight/install`, `lightweight/security/auth`, `lightweight/actors/client`, `lightweight/service/dispatch`, `scripts/`, `lightweight/src/test/.../soak` |
| Q | Quick fix `260430-itz` (post-milestone) | complete | 1/1 | `DefaultMsgDispatcherService.consumeLoop` now polls with 100 ms ceiling and respects `running` flag; `stop()` returns ~100 ms instead of waiting full 5 s | `e4378acf8`, `cc9e3e006`, `f0578b36a` | `lightweight/service/dispatch` |

### Deviations / things that drifted from the original plans

- **Repository strategy.** Brief (and PROJECT.md "Key Decisions") prefer Option A — a separate repository. Actual layout: `lightweight/` is a sibling directory inside the existing `tbmq` repo, with a standalone Maven project (`groupId=org.thingsboard, artifactId=tbmq-lightweight, version=1.0.0-SNAPSHOT`) that is *not* listed in `pom.xml`'s `<modules>`. This is "Option A in spirit" (independent build, independent classpath, no shared runtime code) but "Option B in git layout" (same repo). PROJECT.md correctly records the decision as "— Pending".
- **Phase 7 commit `b5a2d85e3` "complete phase execution" was followed by 6 code-review fix commits** (CR-01, CR-02, WR-01..WR-04). Phase was re-verified after fixes (`cc4833f22` UAT session, `b6a369191` UAT pass) — net effect is correct, but the "complete" label preceded the fixes.
- **Quick fix on 2026-04-30 (`e4378acf8`)** showed that the Phase 7 CR-02 fix (`662fd2405`, "guard dispatch() against null queue") silently broke three pre-existing dispatcher tests that had been latent-failing since CR-02 landed. The Apr 30 task fixes both the `consumeLoop` shutdown bug *and* the broken test scaffold. This is the kind of issue a real ARM64/docker soak run would catch earlier.
- **OPS-01** (Prometheus endpoint) was originally bound to Phase 7 only, but the metrics infrastructure was actually built incrementally across Phase 1 (JVM + connection-active gauge), Phase 4 (auth counters), and Phase 7 (message rates, dispatch depth, RocksDB latency). Verification correctly walks all three.
- **PROTO-07** (Clean Session) is shipped as "Clean Session always-on" — the broker accepts `cleanSession=false` from clients but treats it as `true` because R1 has no persistent sessions. Confirmed by `MqttSessionIntegrationTest.testCleanSession_false_treatedAsTrue` and the WR-01 fix (`b3e1f434e`) which logs the *enforced* value, not the requested one.

## 3. Release 1 coverage table

Mapping the brief's "Release 1 — Core" feature list to shipped code, requirement IDs, phases, and commits.

| R1 feature (brief) | Status | Phase / commits | Evidence in code | Notes / gaps |
|--------------------|--------|-----------------|------------------|--------------|
| Full MQTT 3.1.1 protocol (CONNECT, PUBLISH, SUBSCRIBE, UNSUBSCRIBE, PING, DISCONNECT, QoS 0/1/2) | shipped | Phase 2 — `bff25561d`; PROTO-01..04 | `ClientActor.java`, `MqttSessionHandler.java`, `DefaultSubscriptionRegistry.java`, 13 integration tests | All 9/9 verification truths green; QoS 2 DUP retransmit test `@Disabled` (Paho can't simulate DUP), inbound dedup logic still implemented |
| Full MQTT 5.0 protocol (session expiry, user props, reason codes, topic aliases, shared subs) | shipped | Phase 6 — `8d44c9394`; PROTO-08, 09, 10 | `MqttPropertiesUtil.java` (325 lines), `MqttReasonCodeResolver.java`, `TopicAliasCtx.java`, `Mqtt5Configuration.java`, `DefaultMsgDispatcherService.java` (round-robin), 27 v5 integration tests | REQUIREMENTS.md still shows PROTO-08/09/10 as `[ ]` — file is stale; verification + HUMAN-UAT both PASS |
| In-memory session state, not persisted across restarts | shipped | Phase 2 — `bff25561d`; PROTO-07 | `ClientSessionRegistry`, `ClientSessionCtx` (volatile fields, no persistence path) | Working as designed |
| Non-persistent messaging (Clean Session only in R1) | shipped | Phase 2; PROTO-07 | `ClientActor.processConnect` always treats session as clean | Brief language matches; explicit log line via WR-01 |
| Retained messages (in-memory only) | shipped | Phase 2 + Phase 3 wildcard match; PROTO-05 | `DefaultRetainedMsgService`, `ConcurrentMapRetainMsgTrie` (255 lines), MQTT 5 expiry filter | Brief explicitly defers persistence; matches |
| Last Will and Testament | shipped | Phase 2; PROTO-06, PROTO-11 | `DefaultLastWillService` (LWT keyed by session UUID — correct takeover isolation), `MqttLwtIntegrationTest` (3/3 pass), `MqttClientTakeoverTest` (3/3 pass) | LWT-suppression-on-takeover verified; `DisconnectReasonType.ON_CONFLICTING_SESSIONS.allowsLastWillOnDisconnect()` returns false |
| TLS termination via mounted certificates | shipped | Phase 4 — `951c15b91`; TRAN-02 | `MqttSslServerBootstrap` (phase=1), `MqttSslHandlerProvider`, `PemSslCredentials`, BouncyCastle PEM loading | Tests use `classpath:` prefix; **filesystem-path TLS is on Phase 4 HUMAN-UAT and still pending** (not blocked, just not run) |
| MQTT over WebSocket (`ws://`, `wss://`) | shipped | Phase 5 — `5ced2d580`; TRAN-03, 04, 05 | `MqttWsServerBootstrap` (port 8084), `MqttWssServerBootstrap` (port 8085), 4 frame handlers, `WebSocketServerProtocolHandler(WS_PATH, "mqttv3.1,mqtt")` | WSS gated on `tbmq.tls.enabled=true && tbmq.wss.enabled=true`; correct `Sec-WebSocket-Protocol: mqtt` echo verified by Paho handshake |
| Username/password auth + X.509 client cert auth (RocksDB-backed) | shipped | Phase 4 — `951c15b91`; AUTH-01, 02, 04, 05 | `DefaultLightweightCredentialService` (Caffeine + RocksDB), `DefaultLightweightAuthService.tryBasicAuth/trySSLAuth`, `DefaultCredentialsInstaller` (default tbmq/tbmq), `MqttMtlsIntegrationTest` 3/3 | Anonymous disabled by default (`TBMQ_SECURITY_ANONYMOUS_ENABLED=false`); credentials persistence-across-restart is a Phase 4 HUMAN-UAT item — needs docker run, still pending |
| Topic-level ACL (RocksDB-backed) | shipped | Phase 4; AUTH-03 | `DefaultAuthorizationRuleService` (regex `Pattern.compile()` from stored strings), per-session `authRulePatterns` cached in `ClientSessionCtx` | PUBLISH ACL denial sends QoS acks before dropping (Phase 4 decision); SUBSCRIBE denial returns 0x80 |
| Basic Prometheus `/metrics` endpoint | shipped | Phase 7 — `b5a2d85e3` (and earlier increments in Phase 1, 4); OPS-01 | Endpoint `/actuator/prometheus`; counters: `mqtt.messages.{received,delivered}.total`, `mqtt.dispatch.dropped.total`, `mqtt.auth.{success,failure}.total`; gauges: `mqtt.connections.active`, `mqtt.dispatch.queue.depth`; timer: `rocksdb.read.latency` | REQUIREMENTS.md still shows OPS-01 `[ ]`; verification `PARTIALLY SATISFIED` (under-load assertion via 1-minute soak only) |
| Single Docker image (`docker run -p 1883:1883 thingsboard/tbmq-lightweight`) | shipped | Phase 1 — `b03083edc`, refined Phase 7; OPS-02..06 | `lightweight/docker/Dockerfile` (multi-stage, `eclipse-temurin:17-jre-jammy`, glibc — explicitly NOT alpine for RocksDB JNI on ARM64), `VOLUME ["/data/rocksdb"]`, `EXPOSE 1883 8083`, `HEALTHCHECK` via actuator/health | Multi-arch x86 verified; **arm64 verified only structurally — physical-hardware run is the open Phase 7 SC-3 item** |

## 4. What's left for R1

### Pre-release gates (must finish before "shipped")

1. ~~**Phase 7 SC-3 — ARM64 hardware validation.** `scripts/arm64-validate.sh` exists, is executable, and has valid bash syntax. **Nobody has run it on a real ARM64 device.** Roadmap explicitly excludes QEMU emulation. This is the single largest open R1 item. Owner action: run on a Raspberry Pi 4/5, AWS Graviton, or M-series Mac with Docker Desktop.~~ — **Done 2026-05-07** on AWS EC2 t4g.small Graviton (Ubuntu 26.04 ARM64) using `dlandiak2110/tbmq-lightweight:latest`. All four canonical checks passed; full evidence in `07-VERIFICATION.md` (status: complete) and `07-HUMAN-UAT.md` (status: complete).
2. ~~**Phase 7 SC-4 — Docker logs banner visibility.** `StartupWarningService` is wired and unit-tested, but the WARN-level banner has only been seen in test output, never in real `docker logs`. Quick visual check during the ARM64 run.~~ — **Done 2026-04-30** (user-confirmed: banner observed in real `docker logs`).
3. ~~**Phase 7 SC-2 — full 24-hour soak.** Infrastructure is complete; only a 1-minute smoke run was executed (140 008 messages, 0 leaks, heap stable). Owner action: schedule the 24-hour run before public release. Not strictly blocking (test correctness is duration-independent) but flagged in the brief as a release gate.~~ — **Closed 2026-04-30 as OPTIONAL** (user decision). The 1-minute smoke run on 2026-04-12 (140 008 msgs, 0 ByteBuf leaks under PARANOID, heap stable) is the canonical SC-2 evidence; correctness is duration-independent.

### Documentation drift to fix

4. ~~**REQUIREMENTS.md is out of date.** PROTO-08, PROTO-09, PROTO-10, OPS-01 are still `[ ]` despite VERIFICATION.md marking them SATISFIED. The Traceability table at the bottom has the same drift.~~ — **Done 2026-04-30** (`4c039b49e`).
5. ~~**ROADMAP.md progress-table contradiction.** Phase 7 row shows `0/2 In Progress`, but plans 07-01 and 07-02 are listed `[x]` in the same file. PROJECT.md correctly says complete; ROADMAP.md table didn't get the final tick.~~ — **Done 2026-04-30** (`4c039b49e`).
6. ~~**STATE.md `status: verifying`.** Should be `complete` for milestone v1.0 archival, or stay at `verifying` until SC-3 + SC-4 are signed off. — **Kept at `verifying`** until SC-2/SC-3/SC-4 are signed off.~~ — **Done 2026-05-07.** Flipped to `status: complete` after SC-3 passed. All four Phase 7 success criteria now signed off.

### Suggested next phases (post-R1)

7. **Phase 8 (suggested) — release packaging.** Build & push multi-arch Docker images to Docker Hub (`thingsboard/tbmq-lightweight:1.0.0` + `:latest`), publish a public README, write a quick-start doc. Depends on (1)–(3) being green. Rough scope: 1-2 days. — **Status update 2026-05-07:** Multi-arch buildx + push has been validated end-to-end against a test registry (`dlandiak2110/tbmq-lightweight:1.0.0` + `:latest`, both arches). Production `thingsboard/` push and the public README are deferred until **after R2** per user decision (in-tree sibling project until then; user-facing docs paired with the eventual repo-split decision). CI workflow item is also dropped from this scope.
8. **Phase 9 (suggested) — observability tightening.** Phase 7 anti-patterns:
   - ~~`StartupWarningService.java:79` always emits the "retained in-memory" warning so the `if (!warnings.isEmpty())` guard is dead code (alert fatigue)~~ — **Stale claim.** WR-04 (`5d25fbf3d`) already moved the retained-in-memory line to `log.info(...)` at line 76; the WARN banner at line 79 is correctly guarded.
   - ~~`SoakTest.java:239` has a Python-style `{:.0f}` SLF4J placeholder~~ — **Done 2026-04-30** (`46143e19d`).

### Known technical debt that survived hardening

9. ~~**`ClientActor.java:203-215` (Phase 7 CR-01 fix).** Displaced actor on session takeover does not get explicitly stopped; QoS state maps leak until GC. Mitigation present but not a clean stop. Material under high-reconnect load.~~ — **Resolved 2026-05-04** during R1 code review pass on `lightweight/v1`. Both halves of this concern are closed: (a) QoS state leak — already fully fixed by Phase 7 CR-01 (`6dc0166e0`) which clears `inboundQos2`, `outboundQos1`, `outboundQos2`, and releases all packet IDs at lines 209-213; (b) "explicit stop()" — based on a misreading of the architecture: ClientActor is keyed by **clientId** (not sessionId) and reused across reconnects, so there is no displaced actor object — only a displaced `ClientSessionCtx` whose heavy state is cleared (CR-01), channel closed, LWT removed, subscriptions removed, and whose local reference falls out of scope at end of `processConnect`. The R1 review T5 polish pass surveyed `ClientActor` and rejected further changes; T11 (TbActorMailbox concurrency review) was rejected as the existing pattern is sound. The actual takeover defect was a separate timing race (channelInactive → SessionCloseMsg misdelivery) fixed by **T10** (`9da6ee54f`) + **T10b** (`5ddc867b1`). See `lightweight/REVIEW_REPORT.md` for the full audit trail.
10. ~~**`DefaultMsgDispatcherService.dispatch()` race window.** Apr 30 quick fix established cooperative shutdown via the `running` flag, but `dispatch()` still calls `queue.offer()` before re-checking the queue isn't being drained.~~ — **Done 2026-04-30** (`0029a4377`). Practically-closed via orphan drain at the end of `stop()`: messages that land in the queue between consumer-exit and stop()-return are now counted on `mqtt.dispatch.dropped.total` instead of being silently lost. A nanosecond-scale residual remains (a `dispatch()` call paused for the entire duration of `stop()` could land an offer post-drain); the documented hard-correct upgrade path is a `ReadWriteLock` on `dispatch()` / `stop()` if benchmarks ever require it.
11. ~~**`BrokerMetricsService.java:1` — missing Apache 2.0 license header.** Trivial; flagged as Info in `07-VERIFICATION.md`.~~ — **Done 2026-04-30** (`0d906f4e9`).

## 5. Resolved vs. still-open decisions

Walking through the brief's "Open decisions" section:

| Brief decision | Resolution | Where decided / shipped | Status |
|----------------|------------|--------------------------|--------|
| Repository name & org structure (`tbmq-lightweight` / `tbmq-lite` / `tbmq-standalone`; separate org or under `thingsboard/`) | **Deferred through R2 (2026-05-07 user decision).** `lightweight/` stays as in-tree sibling project alongside the main TBMQ codebase in this repo for the duration of R1 + R2; revisit the repo-split question only after R2 ships. PROJECT.md "Key Decisions" row updated 2026-05-07: Pending → "Deferred — `lightweight/` stays as in-tree sibling project through R1 + R2; revisit post-R2" | `lightweight/pom.xml`, root `pom.xml` (lightweight not in `<modules>`), PROJECT.md Key Decisions table | **Deferred to post-R2** |
| Embedded storage selection (RocksDB vs H2 vs Chronicle Map) | **Resolved → RocksDB 9.7.4.** Chosen in Phase 1 because (a) already in TB ecosystem, (b) battle-tested KV, (c) small footprint. Brief's caveat about ARM64 JNI was addressed by hard-blocking Alpine in the Dockerfile (glibc-only) | `lightweight/storage/rocksdb/DefaultRocksDbStorage.java`, `lightweight/docker/Dockerfile` (warning comment), Phase 1 SUMMARY | **Resolved** |
| In-process queue implementation (Disruptor vs `BlockingQueue`) | **Resolved → `LinkedBlockingQueue` for R1; Disruptor deferred unless benchmarks show saturation.** Decision logged in PROJECT.md | `lightweight/service/dispatch/LinkedBlockingQueueFactory.java` (42 lines), `PublishMsgQueueFactory` interface for swap path | **Resolved with deferred upgrade clause** |
| R1 feature boundary for QoS — should QoS 1/2 to currently-connected clients ship in R1? | **Resolved → yes, in-flight only, no offline queue.** Phase 2 ships full QoS 0/1/2 handshakes (PUBACK / PUBREC+PUBREL+PUBCOMP) to connected subscribers; offline queuing remains R2 | `MqttQosIntegrationTest`, `ClientActor.processPublish/processPubRel/processPubComp` | **Resolved** |
| Metrics & observability — Prometheus in R1 or defer to R2? | **Resolved → Prometheus is in R1.** OPS-01 is a Phase 7 commitment | `lightweight/metrics/BrokerMetricsService.java`, `/actuator/prometheus` | **Resolved** |
| Default data directory & volume mount convention | **Resolved → `/data/rocksdb`.** Set as `VOLUME` in Dockerfile and as `TBMQ_ROCKSDB_PATH` default in `tbmq-lightweight.yml` | `lightweight/docker/Dockerfile`, `lightweight/src/main/resources/tbmq-lightweight.yml` | **Resolved** |

## 6. Future-release backlog reminder (from the brief)

Explicitly out of scope for R1, scheduled for R2+. None of these are present in `lightweight/`.

| Item | Brief category | TBMQ Lightweight requirement ID | Notes |
|------|----------------|----------------------------------|-------|
| Persistent sessions / offline QoS 1/2 message queuing | Future | PERS-01 (REQUIREMENTS.md v2 list) | Brief identifies this as the biggest user-visible R1 gap vs. Mosquitto |
| Durable subscription persistence across restarts | Future | PERS-02 | RocksDB schema in R1 should be forward-compatible — flagged as a STATE.md "Blockers/Concerns" item: "R2 forward-compatible storage layout: R1 RocksDB schema should be designed to accommodate future persistent session columns without migration pain" |
| Persistent retained messages across restarts | Future | PERS-03 | Phase 1 reserved a `RETAINED_MESSAGES` column family — *the column family exists in `RocksDbColumnFamily` enum but is currently unused in production code*; storage groundwork is laid for R2 |
| ThingsBoard PE/CE integration (forward MQTT to TB rule engine) | Future | INTG-01 | Standalone in R1, integration deferred |
| Web UI (TBMQ admin UI bundled into the lightweight image) | Future | MGMT-02 | `ui-ngx/` exists in this repo as the *standard* TBMQ UI; not bundled into `lightweight/` artifact |
| Per-client / per-topic rate limiting | Future | INTG-02 | TBMQ has Bucket4j-based rate limiting; lightweight has no rate-limit module |
| WSS load balancing | Future | n/a | Not applicable without clustering |
| REST management API | Future | MGMT-01 | Out of scope for R1; only `/actuator/*` is exposed |
| Hot-reload of credentials/ACL without restart | Future | MGMT-03 | Not implemented — credentials read on every CONNECT, but no admin write path beyond the default installer |

## 7. Risks & follow-ups

### Risks from the brief that are now relevant

| Risk (from brief) | Current relevance | Recommendation |
|-------------------|-------------------|----------------|
| In-process queue becomes a bottleneck at high message rates | **Unverified.** Default queue capacity is 100 k; soak ran 1 minute at 500 clients × ~140 008 msgs total ≈ 2 333 msg/s. Brief's "tens of thousands of concurrent connections" target is not yet benchmarked | Run a sustained 10 k-connection / 50 k msg/s benchmark before claiming the target. Brief's "should we use Disruptor" can be re-opened if numbers fall short. STATE.md "Blockers/Concerns" already flags: "Performance targets: No explicit throughput floor defined. Recommended floor: 10,000 concurrent connections and 50,000 msg/sec sustained." |
| RocksDB JNI + ARM64 native-library packaging | **Mitigated and verified (2026-05-07).** Dockerfile uses glibc-base image (correct); SC-3 validated on AWS EC2 t4g.small Graviton (Ubuntu 26.04 ARM64) — broker started cleanly, no `UnsatisfiedLinkError`, RocksDB credentials persisted across container restart | None — risk closed |
| Feature-gap confusion between Lightweight and standard TBMQ | **Deferred to post-R2 (2026-05-07 user decision).** No public README in `lightweight/` aimed at end users yet; user-facing docs are paired with the eventual repo-split decision | Write `lightweight/README.md` (quick-start, limitations, decision matrix) **after R2 ships** — not a v1.0 gate |
| No persistent sessions in R1 surprises Mosquitto migrators | **Deferred to post-R2 (paired with README above).** R2 roadmap exists in PROJECT.md but is not surfaced to users | Add a "Limitations" section to the post-R2 public README; link to a tracked R2 issue |

### New risks surfaced during execution (not in the brief)

12. **Documentation drift across PROJECT.md / REQUIREMENTS.md / ROADMAP.md / STATE.md.** Three of the four planning files have inconsistencies (REQUIREMENTS unticked items, ROADMAP progress table, STATE status). Keep the GSD `complete-milestone` flow in mind when archiving — it should sweep these.
13. **Phase 7 was marked "complete" before code-review fixes were applied.** Sequence: `b5a2d85e3` (complete) → `56a9ecb05` (review report) → 6 CR/WR fixes → re-verify. The fixes were real defects (null-password auth bypass, ungraceful dispatcher shutdown, MQTT 5 ACK headers). Worth tightening the workflow: "complete" should land *after* `code-review-fix`.
14. **Repository extraction risk.** The longer `lightweight/` lives inside the main TBMQ repo with no module wiring, the more it looks like a permanent fork-in-place. Brief's stated rationale for Option A (faster cadence, simpler contribution story) is forfeited until the actual extraction happens.
15. **Stop-on-empty-queue regression latent for ~6 weeks.** The Apr 30 quick fix `260430-itz` revealed that the Phase 7 CR-02 fix had been silently breaking three pre-existing tests since it landed. There is no CI gate on the `lightweight/` Maven project. Adding one (`mvn -f lightweight/pom.xml verify` on every PR) would catch the next regression earlier. — **CI workflow dropped 2026-05-07 (user decision).** Risk acknowledged and accepted; not a v1.0 gate. Local `mvn test` runs continue to be the safety net through R1; revisit if regression cost grows.
16. **`RETAINED_MESSAGES` RocksDB column family is reserved but unused.** R2 will need to write to it; ensure migration path stays clean (the brief's "forward-compatible storage layout" concern, already in STATE.md).

### Concrete follow-up checklist

- [x] Run `scripts/arm64-validate.sh` on real ARM64 hardware (Phase 7 SC-3) _(done 2026-05-07 on AWS EC2 t4g.small Graviton, Ubuntu 26.04 ARM64, image `dlandiak2110/tbmq-lightweight:latest` — all four canonical checks passed; evidence in `07-VERIFICATION.md` / `07-HUMAN-UAT.md`)_
- [x] Visually confirm the `STARTUP WARNINGS` banner in real `docker logs` (Phase 7 SC-4) _(user-confirmed 2026-04-30)_
- [x] ~~Run the 24-hour soak with `mvn test -Dgroups=soak -Dsoak.duration.minutes=1440`~~ _(closed 2026-04-30 as OPTIONAL — 1-minute smoke is canonical SC-2 evidence)_
- [x] Sweep REQUIREMENTS.md — tick PROTO-08/09/10, OPS-01; update Traceability table _(done 2026-04-30, `4c039b49e`)_
- [x] Reconcile ROADMAP.md Phase 7 progress row to `2/2 Complete` _(done 2026-04-30, `4c039b49e`)_
- [x] ~~Item 10 — `DefaultMsgDispatcherService` `dispatch()` / `stop()` race window~~ _(done 2026-04-30, `0029a4377` — orphan drain in `stop()` converts silent loss to counted loss on `mqtt.dispatch.dropped.total`; spec & plan in `docs/superpowers/`)_
- [x] ~~Item 9 — `ClientActor.java` displaced-actor leak~~ _(resolved 2026-05-04 during R1 code review pass on `lightweight/v1` — see §0 / §4 entry; QoS-state half closed by Phase 7 CR-01, "explicit stop()" half rested on a misreading; T10 + T10b fixed the actual takeover race)_
- [x] Update STATE.md `status` from `verifying` to `complete` _(done 2026-05-07 after SC-3 passed)_
- [x] ~~Decide repository strategy: extract `lightweight/` to its own repo (per brief), or formally amend PROJECT.md to record "in-tree sibling project" as the chosen variant~~ _(deferred through R2 — 2026-05-07 user decision; `lightweight/` stays as in-tree sibling project until after R2 ships; PROJECT.md Key Decisions row updated)_
- [x] ~~Add a CI workflow that runs `mvn -f lightweight/pom.xml verify` on PRs~~ _(dropped 2026-05-07 — user decision; not a v1.0 gate)_
- [x] ~~Write a public-facing `lightweight/README.md` (quick-start, limitations, decision matrix vs. standard TBMQ)~~ _(deferred until after R2 — 2026-05-07 user decision; paired with the eventual repo-split decision)_
- [x] Build and push multi-arch Docker images _(done 2026-05-07 to test registry `dlandiak2110/tbmq-lightweight:1.0.0` + `:latest`; both `linux/amd64` and `linux/arm64` in OCI manifest index `sha256:98554b72...`. Production push to `thingsboard/tbmq-lightweight` org would need separate Docker Hub credentials and is not pursued here)_

---

*Generated 2026-04-30 by read-only audit of `tbmq-lightweight-brief.md` against `.planning/` and `lightweight/`. Original audit was read-only; the 2026-04-30 follow-up pass (Section 0) committed two atomic doc commits, two atomic code commits, and a code+test fix closing item 10 — see commits `4c039b49e`, `0d906f4e9`, `46143e19d`, `0029a4377`, `7f647074f`.*
