---
title: TBMQ Lightweight — R1 Code Review & Improvement Pass
date: 2026-05-04
author: dlandiak
status: approved-for-planning
review_profile: B (surgical + idiomatic polish)
scope_root: lightweight/
reference_root: application/, common/ (read-only)
---

# TBMQ Lightweight — R1 Code Review & Improvement Pass

## 1. Goal

Improve `lightweight/` by cherry-picking patterns from main TBMQ where main is demonstrably better, without breaking behavior, expanding R1 scope, or reintroducing the heavy dependencies (Kafka, PostgreSQL, Redis/Valkey) that lightweight is explicitly designed to avoid.

Main TBMQ is **not** a gold standard. We cherry-pick what's better; we leave what isn't. Where main is over-engineered or shaped by clustering, the lightweight approach stays.

## 2. Non-goals (hard constraints)

- No new external runtime dependencies; the Docker image stays a single self-contained binary.
- No clustering, no distributed coordination, no multi-broker abstractions.
- No persistent sessions, no offline-message queuing, no durable subscriptions (R1 is Clean-Session-only).
- No ThingsBoard PE/CE integration code.
- Do not touch anything outside `lightweight/`.
- Public surface stays stable: configuration property names, MQTT-visible behavior, Docker entrypoint, ports, volume mount paths.
- All existing lightweight tests must pass after every group.
- No big-bang rewrites. If a class needs a substantial rewrite it goes in its own group with its own justification.

## 3. Review profile

**Profile B — surgical + idiomatic polish.** Cherry-pick patterns from main where lightweight has a demonstrable defect or correctness gap, plus a final low-risk group of small idiomatic cleanups bounded to files already touched by earlier groups (no driveby refactors).

## 4. Themes (review groups)

Each theme below is a **candidate**; the writing-plans phase will validate every claim against actual source before committing it to the executable plan. Order is roughly low-risk → hot-zone.

### Low risk

#### T1 — Cache `TbTypeActorId` per session (allocation on hot path)
- **Problem:** `MqttSessionHandler.java:214, 222, 230, 239, 248, 257, 266, 325, 341, 350, 368` — every inbound MQTT packet allocates a fresh `new TbTypeActorId("client", clientId)` (11 sites). On the publish hot path this is 1 allocation per PUBLISH, plus 1 per PUBACK/PUBREC/PUBREL/PUBCOMP for QoS > 0.
- **Change:** Allocate the `TbTypeActorId` once in `processConnect` (replacing the line 325 site), store it on `ClientSessionCtx`, and reuse the cached reference at all other sites.
- **Source pattern in main:** Equivalent caching pattern in `application/src/main/java/org/thingsboard/mqtt/broker/server/MqttSessionHandler.java` (verify exact lines in plan phase).
- **Blast radius:** `MqttSessionHandler`, `ClientSessionCtx`. No public-surface change.
- **Test impact:** Existing protocol integration tests cover the call sites; add no new tests unless a path becomes uncovered.

#### T2 — Collapse `MqttSessionHandler` boilerplate
- **Problem:** Seven duplicated early-return guards on `sessionCtx == null` (lines 170, 219, 227, 235, 244, 253, 262) plus one inverted variant `if (sessionCtx != null)` in `processPing` (line 349). Four near-identical packet-id-only handlers (`processPubAck` / `PubRec` / `PubRel` / `PubComp`, lines 234–268) differ only in the `MqttMsg` constructor invoked. Duplication makes future cross-cutting changes (metrics, error handling) error-prone.
- **Change:** Extract a `tellActor(TbActorMsg)` helper that asserts session presence and routes to the cached actor ID from T1. Collapse the four packet-id handlers into one parameterized dispatch, or unify under a switch expression keyed on `MqttMessageType`.
- **Source pattern in main:** Tighter switch-and-tell idiom in main's `MqttSessionHandler`.
- **Blast radius:** `MqttSessionHandler` only. LoC drops; behavior identical.
- **Test impact:** None new; existing MQTT QoS / subscribe / unsubscribe tests cover all branches.

#### T3 — Centralize MQTT 5 disconnect-reason mapping in `MqttReasonCodeResolver`
- **Problem:** `MqttSessionHandler.disconnect()` (lines 392–417) inlines an `if/else` mapping `DisconnectReasonType` → `MqttReasonCodes.Disconnect`. The dedicated class `MqttReasonCodeResolver` already exists and already owns related mapping logic.
- **Change:** Move the inline `if/else` into a new `MqttReasonCodeResolver.disconnectReasonFor(DisconnectReasonType)` method. Single source of truth; future reason-code additions land in one place.
- **Source pattern in main:** Main centralizes equivalent logic in its `MqttReasonCodeResolver`.
- **Blast radius:** `MqttSessionHandler`, `MqttReasonCodeResolver`. No public-surface change.
- **Test impact:** Existing MQTT 5 reason-code tests in `Mqtt5ReasonCodeTest` cover the mapping; add a small unit test for the new resolver method.

#### T4 — Logging discipline pass
- **Problem:** A handful of `log.warn` / `log.error` sites fire on benign normal-operation events. Examples to verify in plan phase: `MqttSessionHandler.exceptionCaught` (line 382) at `error` for any throwable including remote resets; channel-inactive races; possibly `DefaultMsgDispatcherService` warn-on-empty.
- **Change:** Triage each site against main's chosen level. Demote where (a) the path is reachable in normal operation and (b) main demonstrably uses a lower level.
- **Source pattern in main:** Cross-check matching call sites in `application/src/main/java/...`.
- **Blast radius:** Multiple files; each change is one-line and trivially reviewable.
- **Test impact:** None new (log levels are not asserted).

#### T5 — Idiomatic polish (the "B" addition)
- **Problem:** Minor idiom drift between lightweight and main (Lombok consistency in service impls, a couple of `switch` blocks that read better as expressions, residual field injection, missed `@RequiredArgsConstructor` opportunities).
- **Change:** Targeted cleanups *strictly limited to files already touched by T1–T4 in this pass*. No driveby refactors.
- **Source pattern in main:** Various — case-by-case.
- **Blast radius:** Same files as T1–T4.
- **Test impact:** None new.

### Medium risk

#### T6 — Replace `TopicAliasCtx` runtime exceptions with a result type
- **Problem:** `MqttSessionHandler.processPublish` (lines 192–212) catches `RuntimeException` from `TopicAliasCtx.getTopicNameByAlias` to detect invalid aliases. Exception-as-control-flow on the inbound publish hot path: every invalid alias allocates a stack trace.
- **Change:** Replace the throwing API with a typed result (an enum or `Optional<String>`). One read site updated; thrown-exception path removed.
- **Source pattern in main:** Main has a `TopicAliasResult` enum (verify exact form in plan phase).
- **Blast radius:** `TopicAliasCtx`, `MqttSessionHandler.processPublish`. No public-surface change.
- **Test impact:** Existing `Mqtt5TopicAliasTest` covers happy + error paths. Add a unit test for the new result type if not already covered.
- **Flag:** Touches MQTT protocol behavior — must verify no externally observable change.

#### T7 — Introduce `ProtocolViolationException` (typed protocol errors)
- **Problem:** Lightweight has no dedicated exception type for MQTT protocol violations. `MqttSessionHandler` calls `disconnect(ctx, DisconnectReasonType.ON_PROTOCOL_ERROR, "<string>")` from many sites, scattering the policy of "what counts as a protocol violation" across the handler.
- **Change:** Introduce a `ProtocolViolationException` carrying `DisconnectReasonType` + message. Validation helpers throw it; the handler catches once at the top of `processMqttMsg` and routes to a single `disconnect-on-protocol-violation` path.
- **Source pattern in main:** `application/src/main/java/org/thingsboard/mqtt/broker/exception/ProtocolViolationException.java`.
- **Blast radius:** `MqttSessionHandler`, new exception class, possibly `MqttPropertiesUtil`/`TopicAliasCtx` if they grow validation helpers.
- **Test impact:** No new tests required; existing protocol-error integration tests assert externally observable behavior.

#### T8 — `LastWillService` lifecycle + observability
- **Problem:** `DefaultLastWillService` has no `@PostConstruct/@PreDestroy` and no metrics. Operators have zero visibility into LWT firing rates.
- **Change:** Add `@PreDestroy` to clear the in-memory map (defensive; not a defect today). Add a Micrometer counter `mqtt.lwt.fired.total` incremented on actual LWT publish.
- **Source pattern in main:** Main's `DefaultLastWillService` has lifecycle hooks and stats.
- **Blast radius:** `DefaultLastWillService`, `BrokerMetricsService` (or wherever counter registration lives).
- **Test impact:** Add a unit test asserting the counter increments on LWT publish; existing `MqttLwtIntegrationTest` covers protocol behavior.

#### T9 — `MqttPropertiesUtil` correctness sweep
- **Problem:** Possible spec-correctness drift vs main: duplicate user-property handling, Topic Alias Maximum bounds checks, message-expiry-interval validation. Need a side-by-side read.
- **Change:** Pull in concrete spec-correctness fixes only. Anything tied to clustering or persistence is rejected.
- **Source pattern in main:** `application/src/main/java/org/thingsboard/mqtt/broker/util/MqttPropertiesUtil.java`.
- **Blast radius:** `MqttPropertiesUtil`, callers of changed methods.
- **Test impact:** Add unit tests for any fix; add MQTT 5 integration tests if the externally observable behavior changes.
- **Flag:** Touches MQTT protocol behavior.

### Hot-zone (conditional — included only if plan-phase reading reveals an actual defect)

#### T10 — `ClientActor` displaced-actor leak
- **Problem (claimed by STATUS.md):** `ClientActor.java:203–215` displaced-actor leak — needs an explicit `stop()` design.
- **Change (conditional):** If reading the code in the plan phase confirms a real leak (actor not unregistered after takeover, or mailbox left open), apply the smallest patch that closes it. If no clean pattern maps from main, punt to a follow-up entry in the report.
- **Source pattern in main:** Main's `ClientActor` takeover path; verify in plan phase.
- **Blast radius:** Actor lifecycle. Hot zone — touches the MQTT protocol state machine indirectly.
- **Test impact:** Add a takeover regression test that asserts the displaced actor is cleaned up.

#### T11 — Actor mailbox concurrency review
- **Problem (claimed by Explore agent):** `TbActorMailbox` uses `ConcurrentLinkedQueue + AtomicBoolean` with a spin-wait pattern that may miss messages on close or burn CPU under contention.
- **Change (conditional):** Read `TbActorMailbox` carefully in the plan phase. If a real defect is present, apply the smallest correct patch. If not, document why and move on.
- **Source pattern in main:** Main delegates to `common/actor/`. Cross-reference the mailbox implementation there.
- **Blast radius:** Every client actor. Hot zone — touches actor framework.
- **Test impact:** Stress test if a defect is found; otherwise none.
- **Flag:** Most likely **rejected** unless plan-phase reading reveals a concrete defect.

## 5. Things lightweight already does *better* than main

These stay as-is. They will be surfaced in `REVIEW_REPORT.md` for possible backport into main.

- **`PingMsg.INSTANCE` singleton** — no allocation per PINGREQ; main allocates per ping.
- **`PacketIdAllocator` as its own class** — cleaner separation than main's `MsgIdSequence` embedded in `ClientSessionCtx`.
- **LWT keyed by session UUID, not clientId** — correct takeover isolation. Decision recorded in Phase 2 STATE.md.
- **`@RequiredArgsConstructor` constructor injection consistently** — main still has legacy `@Autowired` field injection in `BaseController` and elsewhere.
- **No clustering coupling at all** — by design. Main has clustering branches sprinkled into otherwise-single-node code paths.

## 6. Explicitly out of scope (rejected even though main has it)

- RateLimitService / Bucket4j (not R1).
- TbMessageStatsReportClient / historical stats reporter (clustering).
- ClientSubscriptionPersistenceService / Kafka listeners (clustering).
- StatsManager / multi-layer metrics (over-engineered; current `BrokerMetricsService` is sufficient).
- SCRAM enhanced auth (not R1).
- AbstractMqttChannelInitializer / AbstractMqttHandlerProvider abstraction layer (premature for 4 listener variants).

## 7. Workflow

1. **Phase 1 — Brainstorm (this document).**
2. **Phase 2 — Plan.** writing-plans skill produces a per-theme plan with verified source line numbers, blast radius per change, and test impact. Stop at the gate.
3. **Phase 3 — Execute.** executing-plans skill works group-by-group. After each group: run the relevant subset of tests; commit with `[lightweight] <theme>: <description>`. Skip a group if the code review proves it a bad idea; document the skip in the report.
4. **Phase 4 — Validate.** Run the full lightweight test suite (`mvn -f lightweight/pom.xml -o test`). Smoke-test via `docker run`. All green or explicit explanation.
5. **Phase 5 — Deliver.** Single PR with clean commits, plus `REVIEW_REPORT.md` covering: executive summary, per-group findings (changed / source / test impact), items considered and rejected, lightweight-better-than-main backport candidates, test results, R2 follow-ups.

## 8. Test policy

- All tests in `lightweight/` must be green after every group and at the end.
- Where changed code has obviously thin coverage, add unit + integration tests as appropriate.
- Do not add tests for unrelated code; coverage gaps unrelated to this pass go in the report as follow-ups.
- Do not weaken or `@Disabled`/`@Ignore` an existing test. If a test is genuinely wrong, fix it and explain why.

## 9. Success criteria

- [ ] All `lightweight/` tests pass.
- [ ] Smoke test (docker run + Paho client + publish/subscribe round trip) succeeds.
- [ ] No new runtime dependencies.
- [ ] No code outside `lightweight/` modified.
- [ ] No public configuration property renamed/removed without explicit approval in the plan.
- [ ] `REVIEW_REPORT.md` present and complete (per Phase 5 spec).
- [ ] Commit history clean: one commit per planned group.
- [ ] Items rejected during execution documented with reasoning.

## 10. Open items / risks

- T6/T7 touch MQTT protocol behavior. Both must verify no externally observable change in the plan phase.
- T10/T11 are conditional. Plan-phase reading decides whether they enter the executable plan or move to follow-ups.
- Recent dispatcher fix (`0029a4377`) is fresh. T4 may inspect it; any non-trivial change to that area requires a separate explicit plan entry.
