# TBMQ Lightweight R1 Code Review — Report

**Date:** 2026-05-04
**Branch:** `codebase-map`
**Spec:** `docs/superpowers/specs/2026-05-04-tbmq-lightweight-r1-review-design.md`
**Plan:** `docs/superpowers/plans/2026-05-04-tbmq-lightweight-r1-review.md`
**Profile:** B (surgical + idiomatic polish)

## Executive summary

This pass reviewed `lightweight/` against the standard TBMQ codebase under Profile B (surgical, with bounded idiomatic polish). Of eleven candidate themes from the design spec, ten landed (T1–T10 in plan order); T11 (`TbActorMailbox` concurrency review) was rejected after plan-phase reading found the existing pattern sound. Two genuine defects were fixed: a takeover race in `ClientActor.processConnect` that could tear down a brand-new session displacing an old one (Plan-Task 9, follow-up T10b for the same race shape in `processDisconnect`), and a missing `Message Expiry Interval` decrement on retained-on-subscribe delivery in violation of MQTT 5.0 [MQTT-3.3.2-6] (Plan-Task 8). Net code-health impact is positive: 10 allocations per inbound MQTT packet eliminated (T1), seven boilerplate handlers collapsed (T2), `RuntimeException` replaced with a typed protocol-violation exception (T5/T6), exception logging demoted from ERROR to DEBUG/WARN with stack-trace discipline (T4), and a new `mqtt.lwt.fired.total` counter wired up (T7). Test coverage rose from 154 to 172 (Δ +18 across new unit suites and two regression integration tests), with 0 failures and 3 pre-existing skips.

## Per-group findings

### Plan-Task 1 (spec-§4 T1) — Cache `TbTypeActorId` per session
- **Changed:** `ClientSessionCtx.java` (added `volatile TbTypeActorId clientActorId` field), `MqttSessionHandler.java` (single allocation in `processConnect` + `setClientActorId`; replaced 10 occurrences of `new TbTypeActorId("client", clientId)` with `sessionCtx.getClientActorId()`).
- **Source pattern in main:** `application/src/main/java/org/thingsboard/mqtt/broker/server/MqttSessionHandler.java` (similar caching).
- **Net:** -10 allocations per inbound MQTT packet across PUBLISH/PUBACK/PUBREC/PUBREL/PUBCOMP/SUBSCRIBE/UNSUBSCRIBE/PINGREQ/DISCONNECT plus channelInactive.
- **Tests:** No new tests; existing protocol/QoS suites cover all reused-actorId call sites.
- **Commit:** `3c7c295a1`.

### Plan-Task 2 (spec-§4 T2) — Collapse `MqttSessionHandler` boilerplate
- **Changed:** `MqttSessionHandler.java`. Added private helper `tellActor(TbActorMsg)` and collapsed 7 inline-actor-tell handlers (`processSubscribe`, `processUnsubscribe`, `processPubAck`, `processPubRec`, `processPubRel`, `processPubComp`, `processPing`) to one-liners.
- **Net:** Pure boilerplate reduction. `processPublish` retains its session-presence guard (it dereferences `getMqttVersion()`/`getTopicAliasCtx()` before the actor send). `channelInactive` intentionally untouched (specific `TbActorNotRegisteredException` handling is structurally different).
- **Tests:** No new tests; behaviour-preserving refactor.
- **Commit:** `00eca5ac7`.

### Plan-Task 3 (spec-§4 T3) — Centralize MQTT 5 disconnect-reason mapping
- **Changed:** `MqttReasonCodeResolver.java` (new method `disconnectReasonFor(DisconnectReasonType)` returning `MqttReasonCodes.Disconnect`), `MqttSessionHandler.java` (replaced inline if/else mapping in `disconnect()` with single resolver call). New unit suite `MqttReasonCodeResolverTest.java` (4 tests).
- **Net:** Single source of truth for reason-code translation; wire-level coverage from existing `Mqtt5ReasonCodeTest` continues to validate externally observable behaviour.
- **Tests:** +4 (154 → 158).
- **Commit:** `23cc8df99`.

### Plan-Task 4 (spec-§4 T4) — Logging discipline in `MqttSessionHandler.exceptionCaught`
- **Changed:** `MqttSessionHandler.java`. Demoted per-Throwable `log.error(...)` to `log.debug(...)` for `IOException` (subsumes `ClosedChannelException`); other throwables now log at `WARN` (down from `ERROR`) with stack trace.
- **Net:** Eliminates noise from clients that abruptly close sockets — operationally common, not actionable. `DefaultMsgDispatcherService.java` reviewed read-only; its three remaining warn/error sites are operator-actionable (drain timeout, dropped-orphans count, consumer-loop exception) and were left alone.
- **Tests:** None; log levels are not asserted.
- **Commit:** `6f085428b`.

### Plan-Task 5 (spec-§4 T7) — Add `ProtocolViolationException`
- **Changed:** New `lightweight/.../exception/ProtocolViolationException.java` (33 lines). Mirrors main TBMQ's class with additional Javadoc explaining use sites.
- **Net:** Typed exception replaces ad-hoc `RuntimeException` raised on MQTT-protocol contract breaches; lets `MqttSessionHandler.processPublish` narrow its catch arm in Plan-Task 6.
- **Tests:** None; value carrier with no logic.
- **Commit:** `ef72f132d`.

### Plan-Task 6 (spec-§4 T6) — `TopicAliasCtx` uses `ProtocolViolationException`
- **Changed:** `TopicAliasCtx.java` (replaced 4 `throw new RuntimeException(...)` with the typed `ProtocolViolationException`; updated 2 `@throws` Javadoc tags + 1 `{@link RuntimeException}` reference). `MqttSessionHandler.processPublish` narrowed its catch arm. New unit suite `TopicAliasCtxTest.java` (7 tests).
- **Net:** Stronger compile-time contract; protocol-violation paths are now distinguishable from genuine transport failures.
- **Tests:** +7 (158 → 165). Wire-level coverage from existing `Mqtt5TopicAliasTest` continues to pass.
- **Commit:** `f5143cbef`.

### Plan-Task 7 (spec-§4 T8) — `LastWillService` lifecycle + `mqtt.lwt.fired.total` counter
- **Changed:** `BrokerMetricsService.java` (registers counter in `init()`), `DefaultLastWillService.java` (adds `@PreDestroy destroy()` clearing the in-memory map), `ClientActor.processDisconnect` (one-line counter increment at the LWT delivery site), `MqttLwtIntegrationTest.java` (extended one existing positive-LWT test with order-independent before/Awaitility counter assertion).
- **Net:** Observable LWT-fired metric for SRE visibility; lifecycle hook avoids stale-map carry-over across application context refreshes (defense in depth).
- **Tests:** +0 (extension, not addition).
- **Commit:** `c353155df`.

### Plan-Task 8 (spec-§4 T9) — `MqttPropertiesUtil` correctness sweep
- **State:** Initially **REJECTED**, then **APPLIED** after follow-up audit. The first read produced a classification table of ~30 differences vs main; all were caller-helpers (no callers in lightweight), persistence-coupled (out of R1 scope), cosmetic, or equivalent. A second pass discovered that lightweight's `MqttPropertiesUtil.getRemainingExpiryInterval` was **dead code**: the math primitive existed at `MqttPropertiesUtil.java:205` but had no callers — only its own definition and a Javadoc reference. As a result, `copyPublishPropertiesToDeliver` was shipping the original `Message Expiry Interval` on retained-on-subscribe delivery in violation of MQTT 5.0 [MQTT-3.3.2-6] ("the PUBLISH packet sent to a Client by the Server MUST contain the Message Expiry Interval set to the received value minus the time that the Application Message has been waiting in the Server").
- **Changed:** `MqttPropertiesUtil.java` (new overload `copyPublishPropertiesToDeliver(MqttProperties, long createdTimeMillis)` delegating to a private `copyPublishProperties(MqttProperties, Long)` helper to keep DRY; existing single-arg overload preserved for live-delivery callers; 0-remaining edge OMITS the property entirely per spec). `ClientActor.java` (single line at `processSubscribe` retained-flush call site to pass `retained.getCreatedTime()`). New `Mqtt5RetainedMsgExpiryTest.java` (Paho v5 integration test with 2.5s real-time dwell asserting the received expiry falls in `[50, 59]`). New `MqttPropertiesUtilTest.java` (4 unit tests covering: non-IntegerProperty fallback, zero-remaining omission, null source, NO_PROPERTIES source).
- **Live-publish path** (`processDeliver` ~line 624) intentionally **left unchanged**: sub-ms in-process dwell makes the decrement always 0 today. R2 follow-up if/when persistent queues are added for offline subscribers.
- **Tests:** +5 (165 → 170).
- **Commits:** `ac20ea319` (fix) + `5b8ed51f2` (polish: test rename + unit coverage).

### Plan-Task 9 (spec-§4 T10) — `ClientActor` displaced-session race fixes
- **Defect (T10):** Verified race trace from plan-phase static analysis:

  ```
  Old TCP connection (sessionCtx_OLD) — actor instance A, sessionCtx field = OLD
    ↓ takeover from new TCP connection arrives
  processConnect (ClientActor) on actor thread:
    L215  oldSession.setState(DISCONNECTING)
    L216  if (oldSession.getChannel().channel().isActive())
    L217      oldSession.getChannel().close()   // ASYNC — schedules close on Netty event loop
    L219  oldSession.setState(DISCONNECTED)     // happens AFTER close is scheduled
  Netty event loop later: channel close completes → fires channelInactive on OLD handler
  OLD MqttSessionHandler.channelInactive may still see DISCONNECTING and tell SessionCloseMsg
  Actor instance A receives SessionCloseMsg, but its sessionCtx field is now NEW (overwritten
  by processSessionInit). processDisconnect fires against the NEW session. Result: brand-new
  client connection torn down with reason ON_CHANNEL_CLOSED.
  ```
- **Fix:** Set `oldSession.setState(SessionState.DISCONNECTED)` *before* `close()`. Volatile happens-before guarantees `channelInactive` observes DISCONNECTED and skips its tell. The intermediate `DISCONNECTING` state in the takeover path is dropped (no logic runs between the two states there).
- **Sibling race (T10b):** Code review of T10 spotted the same race shape in `ClientActor.processDisconnect` — currently benign because `ctx.stop()` removes the actor mailbox immediately after, BUT conditional benignness: rapid clean-disconnect-then-reconnect on the same clientId could deliver a late `SessionCloseMsg` to the freshly-created actor for the new connection. Same fix pattern: move `setState(DISCONNECTED)` above `getChannel().close()`. The earlier `setState(DISCONNECTING)` at line 290 is **kept** because intermediate logic (LWT delivery) runs between DISCONNECTING and the close.
- **Tests:** New `MqttClientTakeoverTest`:
  - `testNewConnectionSurvivesRapidTakeoverRace` — 20-iteration takeover storm + 200ms dwell + round-trip.
  - `testNewConnectionSurvivesRapidReconnectAfterDisconnect` — 20 clean-disconnect-then-reconnect cycles + 200ms dwell + round-trip.
  Both pass post-fix; they passed pre-fix on the test hardware as well (race window narrow), but the static-analysis JMM justification is canonical evidence.
- **Tests:** +2 (170 → 172).
- **Commits:** `9da6ee54f` (T10 takeover) + `5ddc867b1` (T10b sibling race in `processDisconnect`).

### Plan-Task 10 (spec-§4 T5) — Idiomatic polish bounded to files touched by Plan-Tasks 1–9
- **Changed (2 narrow edits):**
  1. `MqttSessionHandler.exceptionCaught`: collapsed redundant `instanceof java.io.IOException || instanceof java.nio.channels.ClosedChannelException` (subclass relationship) to a single `instanceof IOException`; replaced inline FQN with a normal import to match module convention.
  2. `TopicAliasCtx`: removed dead `@Slf4j` annotation + `lombok.extern.slf4j.Slf4j` import — the generated `log` field was never referenced after Plan-Task 6 replaced warn-and-throw with throw-only paths.
- **Surveyed and rejected (no changes needed):** `ClientSessionCtx`, `MqttReasonCodeResolver`, `DefaultLastWillService`, `BrokerMetricsService`, `ClientActor`, `MqttPropertiesUtil`, `ProtocolViolationException`.
- **Tests:** +0.
- **Commit:** `f0e46b327`.

## Items considered and rejected

### T11 — `TbActorMailbox` concurrency review (REJECTED)

Plan-phase reading found the `ConcurrentLinkedQueue + AtomicBoolean` pattern with explicit `busy`/`ready`/`destroyInProgress` flags to be sound. The `forEach` (rather than `drain`) in `destroy()` is benign given `ready=NOT_READY` blocks future `processMailbox` runs. No benchmark or profiler signal exists to justify a change.

### `MqttPropertiesUtil` "caller helper" methods from main TBMQ

Intentionally not pulled. Lightweight's `MqttPropertiesUtil` is a deliberate minimal rewrite, not a strip-fork. Pulling per-property accessor helpers (e.g., `getTopicAliasProperty`, `getResponseInfo`) without callers in lightweight would inflate surface area without correctness benefit.

### Persistence-coupled methods from main TBMQ

Anything tied to `MsgExpiryResult`, `DevicePublishMsg`, or `TbQueueMsgHeaders` is out of R1 scope by design.

### Out-of-scope items not pulled from main

- `RateLimitService` / Bucket4j (not R1).
- `TbMessageStatsReportClient` / historical stats reporter (clustering).
- `ClientSubscriptionPersistenceService` / Kafka listeners (clustering).
- `StatsManager` / multi-layer metrics (over-engineered for single node).
- SCRAM enhanced authentication (not R1).
- `AbstractMqttChannelInitializer` / `AbstractMqttHandlerProvider` abstraction (premature).

## Lightweight is better than main — backport candidates

Patterns where the lightweight implementation should flow back upstream into standard TBMQ:

1. `PingMsg.INSTANCE` singleton (no allocation per PINGREQ).
2. `PacketIdAllocator` as its own class (cleaner than embedded `MsgIdSequence`).
3. LWT keyed by session UUID (correct takeover isolation).
4. Constructor injection consistently via `@RequiredArgsConstructor` (no `@Autowired` field injection).
5. No clustering coupling at all (single-node simplicity).
6. `MqttPropertiesUtil.getIntegerProperty` defensive null + `instanceof` guard (vs. main's unguarded cast).
7. `copyPublishPropertiesToDeliver` whitelist is more disciplined than main's broad `copyProps(MqttProperties)` clone.
8. `ProtocolViolationException` Javadoc is more informative than main's (which has none).

## Test results

- **Final `mvn -f lightweight/pom.xml -o verify`:** Tests run: **172**, Failures: **0**, Errors: **0**, Skipped: **3**.
- **Test count progression across the pass:** 154 → 158 (+T3) → 165 (+T6) → 170 (+T9) → 172 (+T9 race tests). Net Δ +18.
- **New test files created:**
  - `src/test/java/org/thingsboard/mqtt/broker/lightweight/util/MqttReasonCodeResolverTest.java` (T3, 4 tests).
  - `src/test/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtxTest.java` (T6, 7 tests).
  - `src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/Mqtt5RetainedMsgExpiryTest.java` (T9, 1 integration test, Paho v5).
  - `src/test/java/org/thingsboard/mqtt/broker/lightweight/util/MqttPropertiesUtilTest.java` (T9, 4 unit tests).
  - `src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttClientTakeoverTest.java` (T10/T10b, 2 integration tests).
- **Extended integration tests:** `MqttLwtIntegrationTest` (T8 counter assertion).
- **Smoke test (Docker build + run + round-trip):** **PASS** — see details below.

### Docker smoke test outcome

**Status:** PASS

- Image: `tbmq-lightweight-review:smoke` built from `lightweight/docker/Dockerfile` (multi-stage; final size 386 MB, `eclipse-temurin:17-jre-jammy` runtime as required for RocksDB JNI glibc compatibility).
- Container: `docker run --rm -d -p 1883:1883 -p 8083:8083 tbmq-lightweight-review:smoke`.
- Startup: Spring Boot booted in 5.2s. MQTT TCP listener bound to 1883 (epoll), MQTT WS listener bound to 8084, HTTP/Actuator on 8083. Default `tbmq` credentials installed by `DefaultCredentialsInstaller`.
- TCP probe: `nc -z -v 127.0.0.1 1883` → connection succeeded.
- HTTP probe: `curl -sf http://localhost:8083/actuator/health` → `{"status":"UP",...}`.
- MQTT round-trip: `mosquitto_sub -t test/smoke -C 1` (subscriber) + `mosquitto_pub -t test/smoke -m "hello-from-smoke-test"` (publisher), both authenticated as `tbmq:tbmq`. Subscriber received `test/smoke hello-from-smoke-test` and exited 0.
- Log inspection: zero `ERROR` lines, zero stack traces, all client connect/disconnect transitions logged at `INFO` with reason `ON_DISCONNECT_MSG` (clean disconnect path).
- Container stopped cleanly via `docker stop`.

## Open questions / R2 follow-ups

The following items surfaced during execution and are deliberately deferred:

1. **`MqttReasonCodeResolver` cleanup:** vestigial `disconnectProtocolError()` and `disconnectTopicAliasInvalid()` helpers now have no callers after T3. Removable in a separate narrowly-scoped task.
2. **`MqttSessionHandler.exceptionCaught` null guard:** `cause.getMessage()` is not null-guarded (pre-existing pattern; `disconnect(...)` downstream does the same). Harmless in practice; `String.valueOf(...)` would be more robust.
3. **`TopicAliasCtx` outbound path** (`getTopicAliasForPublish`): non-trivial logic with documented TOCTOU; not unit-tested. Worth pinning behaviours like `outboundMax==0` returns 0, alias re-binding, and exhaustion.
4. **`TopicAliasCtx.getTopicNameByAlias` `@throws` Javadoc:** still says "0 or exceeds inbound maximum"; should also mention "or references no stored mapping" after T6.
5. **`mqtt.lwt.fired.total` counter description:** "delivered to subscribers" is slightly imprecise — it actually increments at the dispatch hand-off. "Handed off for dispatch" would be more accurate. A negative-counter test on clean disconnect would defend against future regressions where someone moves the increment outside the `ifPresent` lambda.
6. **`MqttPropertiesUtil` outbound recompute on live-publish path:** today the in-process dwell is sub-ms so the decrement is always 0; revisit if/when persistent queues land for offline subscribers.
7. **Defense-in-depth in `MqttSessionHandler.channelInactive`:** the actor's session-id could be compared to `this.sessionCtx.getSessionId()` to skip the tell when they differ — strictly stronger than the volatile-state fix because it doesn't rely on the close→channelInactive ordering. Today the volatile fix is sufficient.
8. **Counter lookup-by-name overhead:** `meterRegistry.counter(name).increment()` is the existing module-wide pattern (verified across 6 sites); a uniform refactor to cached `Counter` field instances is a Phase 2 candidate.
9. **`Mqtt5RetainedMsgExpiryTest` real-time sleep:** 2.5s `Thread.sleep` is a hard wall-clock pause; on heavily loaded CI the `[50, 59]` jitter window is forgiving but a virtual-time clock injection would be more robust (not in R1 scope).

### Pre-existing items (not part of this pass)

- ARM64 hardware validation (Phase 7 SC-3).
- 24-hour soak run — closed as OPTIONAL per Phase 7 status.
- Phase 4 HUMAN-UAT (filesystem-path TLS, credential persistence-across-restart).
- T11 (`TbActorMailbox`) — re-review only if a benchmark surfaces a concrete contention or shutdown-loss issue.
