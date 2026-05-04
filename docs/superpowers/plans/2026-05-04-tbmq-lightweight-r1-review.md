# TBMQ Lightweight R1 Code Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply Profile B (surgical + idiomatic polish) cherry-picks from main TBMQ into `lightweight/`, ordered low-risk → high-risk, without breaking behavior or expanding R1 scope.

**Architecture:** Each task is a self-contained, commit-sized change touching a small surface. Tasks 1–4 are pure refactors with no observable behavior change; tasks 5–9 add typed errors / observability / a bug fix; task 10 is bounded idiomatic polish over files touched by 1–9. The plan introduces no new dependencies and no new public configuration.

**Tech Stack:** Java 17, Spring Boot 3.5, Netty 4.1.122, Micrometer, JUnit 5, Eclipse Paho MQTT v3 + v5, Awaitility. Built with Maven (`mvn -f lightweight/pom.xml -o test`).

**Source spec:** `docs/superpowers/specs/2026-05-04-tbmq-lightweight-r1-review-design.md`

**Verified findings during plan-phase reading:**
- T10 hot-zone (`ClientActor` displaced-session race): **real defect confirmed**. Race window between `oldSession.setState(DISCONNECTING)` (line 215) → `oldSession.getChannel().close()` (line 217) → `oldSession.setState(DISCONNECTED)` (line 219) allows Netty's `channelInactive` on the old channel to fire while state is still `DISCONNECTING`, sending a `SessionCloseMsg` for the old session that the (reused) actor processes against the *new* `sessionCtx`, incorrectly tearing it down. Included in plan as Task 9.
- T11 hot-zone (`TbActorMailbox` concurrency): **rejected**. The `ConcurrentLinkedQueue + AtomicBoolean` pattern with explicit `busy`/`ready`/`destroyInProgress` flags is sound. The `forEach`-instead-of-`drain` in `destroy()` is a benign pattern given `ready=NOT_READY` blocks future `processMailbox` runs. No benchmark/profiler signal justifies a change. Documented in `REVIEW_REPORT.md` as rejected with reasoning.
- T6 originally proposed a result-enum API in main; main actually still *throws* (a typed `MqttException`). The clean improvement is to replace lightweight's generic `RuntimeException` with the typed `ProtocolViolationException` introduced by Task 5. T6 and T7 therefore fold together: T7 introduces the exception type; T6 wires `TopicAliasCtx` through it. Plan order reflects this dependency.

---

## File-Structure Map

**Files modified across the plan (no new packages):**

- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java` — Tasks 1, 2, 3, 4, 5, 10
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/ClientSessionCtx.java` — Tasks 1, 10
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/util/MqttReasonCodeResolver.java` — Task 3
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtx.java` — Task 6
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/will/DefaultLastWillService.java` — Task 7
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java` — Tasks 7, 9, 10
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsService.java` — Task 7
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java` — Task 4 (review only; touch only if a level mismatch is concrete)

**Files created:**

- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/exception/ProtocolViolationException.java` — Task 5
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/util/MqttReasonCodeResolverTest.java` — Task 3 (only if not already present)
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtxTest.java` — Task 6 (only if not already present)
- `REVIEW_REPORT.md` — Task 11 (delivery)

**Test files exercised (existing, no changes unless noted):**

- `Mqtt5TopicAliasTest.java` — Tasks 1, 2, 6
- `Mqtt5ReasonCodeTest.java` — Task 3
- `MqttQosIntegrationTest.java`, `MqttSubscribeIntegrationTest.java`, `MqttConnectIntegrationTest.java` — Tasks 1, 2
- `MqttLwtIntegrationTest.java` — Task 7
- `MqttClientTakeoverTest.java` — Task 9 (extended with regression test)

---

## Test Runner

Throughout the plan, the canonical test command is:

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test
```

To run a single test class:

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test -Dtest=ClassName
```

To run a single test method:

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test -Dtest=ClassName#methodName
```

Soak tests are excluded by default. **Do not** clear `surefire.excludedGroups` during this pass — soak tests require explicit setup.

The test JVM uses `-Dio.netty.leakDetection.level=PARANOID`. Any ByteBuf leak surfaces as a stderr warning but does not fail the test run; review test output for `LEAK:` if a Netty-touching test is added.

---

## Task 1: Cache `TbTypeActorId` per session (T1)

**Goal:** Eliminate per-packet allocation of `TbTypeActorId` in `MqttSessionHandler` (11 sites). Allocate once in `processConnect`, store on `ClientSessionCtx`, reuse everywhere.

**Files:**
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/ClientSessionCtx.java`
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java`
- Test: existing `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttQosIntegrationTest.java` (and others) — must remain green; no new test required.

**Behavior preservation:** `TbTypeActorId.equals/hashCode` is structural (record-like); a cached instance and a freshly-allocated one with the same `(type, entityId)` are interchangeable for `actorSystem.tell` lookup. No observable change.

- [ ] **Step 1: Add `clientActorId` field to `ClientSessionCtx`**

Edit `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/ClientSessionCtx.java`. Add an import and a new field next to `clientId`:

```java
import org.thingsboard.mqtt.broker.lightweight.actors.TbTypeActorId;
```

Add field (place immediately after the existing `clientId` field declaration at the current line ~63):

```java
    /**
     * Cached actor ID for this session. Allocated once after CONNECT processing and reused
     * for all subsequent {@code actorSystem.tell} calls to avoid per-packet allocation
     * on the inbound MQTT hot path.
     */
    private volatile TbTypeActorId clientActorId;
```

`@Setter` on the class generates `setClientActorId(TbTypeActorId)` automatically.

- [ ] **Step 2: Allocate the cached actor ID in `MqttSessionHandler.processConnect` and store it on the session**

In `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java`, inside `processConnect` at the current line 325 (the `TbTypeActorId actorId = new TbTypeActorId(...)` allocation), set the cached ID on the session immediately after creation:

Change current lines 325–326 from:

```java
        TbTypeActorId actorId = new TbTypeActorId("client", clientId);
        actorSystem.createRootActor(CLIENT_DISPATCHER, new ClientActorCreator(
```

to:

```java
        TbTypeActorId actorId = new TbTypeActorId("client", clientId);
        sessionCtx.setClientActorId(actorId);
        actorSystem.createRootActor(CLIENT_DISPATCHER, new ClientActorCreator(
```

- [ ] **Step 3: Replace per-packet `new TbTypeActorId(...)` calls with the cached field**

In the same file, replace the local `TbTypeActorId actorId = new TbTypeActorId("client", sessionCtx.getClientId());` allocation at every other site (lines 214, 222, 230, 239, 248, 257, 266, 341, 350, 368) with:

```java
        TbTypeActorId actorId = sessionCtx.getClientActorId();
```

After this step, `MqttSessionHandler` should contain exactly **one** `new TbTypeActorId(...)` allocation, in `processConnect`. Verify with:

```bash
grep -n "new TbTypeActorId" /home/dlandiak/projects/gsd/tbmq/lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
```

Expected: a single hit, on the line in `processConnect`.

- [ ] **Step 4: Run the full lightweight test suite**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test
```

Expected: BUILD SUCCESS. All previously-green tests pass. No new tests are added in this task (existing protocol/QoS/subscribe tests cover all reused-actorId call sites).

- [ ] **Step 5: Commit**

```bash
git add lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/ClientSessionCtx.java \
        lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
git commit -m "$(cat <<'EOF'
[lightweight] T1 perf: cache TbTypeActorId per session

Eliminate the per-MQTT-packet allocation of TbTypeActorId in
MqttSessionHandler (11 inbound call sites). Allocate once in
processConnect, store on ClientSessionCtx, reuse for all subsequent
tell()s. No behavior change; equality of TbTypeActorId is structural.
EOF
)"
```

---

## Task 2: Collapse `MqttSessionHandler` boilerplate (T2)

**Goal:** Remove duplicated session-presence guards and packet-id-only handler bodies. Behavior unchanged.

**Files:**
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java`
- Test: existing `MqttQosIntegrationTest`, `MqttSubscribeIntegrationTest`, `MqttKeepAliveIntegrationTest` — must remain green.

**Behavior preservation:** All call sites continue to early-return when `sessionCtx == null` (i.e., before CONNECT). The four packet-id-only handlers continue to construct the same `MqttPub*Msg` types.

- [ ] **Step 1: Add a `tellActor` helper method**

Insert into `MqttSessionHandler.java` immediately above `processPublish` (current line 169):

```java
    /**
     * Sends a message to the cached client actor for this session. Returns silently if no session
     * has been initialized (i.e., before CONNECT). Centralizes the guard + tell pattern that
     * would otherwise be duplicated at every MQTT packet handler.
     */
    private void tellActor(TbActorMsg msg) {
        if (sessionCtx == null) {
            return;
        }
        actorSystem.tell(sessionCtx.getClientActorId(), msg);
    }
```

Add the import if not already present:

```java
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorMsg;
```

- [ ] **Step 2: Collapse the four packet-id-only handlers (`processPubAck`, `processPubRec`, `processPubRel`, `processPubComp`)**

Replace lines 234–268 (the four handlers) with:

```java
    private void processPubAck(MqttMessage msg) {
        tellActor(new MqttPubAckMsg(((MqttMessageIdVariableHeader) msg.variableHeader()).messageId()));
    }

    private void processPubRec(MqttMessage msg) {
        tellActor(new MqttPubRecMsg(((MqttMessageIdVariableHeader) msg.variableHeader()).messageId()));
    }

    private void processPubRel(MqttMessage msg) {
        tellActor(new MqttPubRelMsg(((MqttMessageIdVariableHeader) msg.variableHeader()).messageId()));
    }

    private void processPubComp(MqttMessage msg) {
        tellActor(new MqttPubCompMsg(((MqttMessageIdVariableHeader) msg.variableHeader()).messageId()));
    }
```

- [ ] **Step 3: Use `tellActor` in `processSubscribe`, `processUnsubscribe`, and `processPing`**

Replace `processSubscribe` (current lines 218–224) with:

```java
    private void processSubscribe(MqttSubscribeMessage mqttSubscribeMessage) {
        tellActor(new MqttSubscribeMsg(mqttSubscribeMessage));
    }
```

Replace `processUnsubscribe` (current lines 226–232) with:

```java
    private void processUnsubscribe(MqttUnsubscribeMessage mqttUnsubscribeMessage) {
        tellActor(new MqttUnsubscribeMsg(mqttUnsubscribeMessage));
    }
```

Replace `processPing` (current lines 348–353) with:

```java
    private void processPing() {
        tellActor(PingMsg.INSTANCE);
    }
```

- [ ] **Step 4: Use `tellActor` in `processPublish`, `processDisconnect`, and `channelInactive`**

`processPublish` retains its session guard (lines 170–172) because it touches `sessionCtx.getMqttVersion()`/`sessionCtx.getTopicAliasCtx()` *before* the actor send. Only the final send line (current line 215) changes. Replace lines 214–215 with:

```java
        tellActor(new MqttPublishMsg(topicName, qos, payloadBytes, retain, dup, packetId, properties));
```

`processDisconnect` (current lines 337–346) retains its `sessionCtx == null` branch because it has a non-trivial else (`ctx.close()`). Replace just the inside of the `sessionCtx != null` block (lines 339–343):

```java
    private void processDisconnect(ChannelHandlerContext ctx) {
        if (sessionCtx != null) {
            // Per D-03: Ignore Session Expiry Interval in DISCONNECT packets
            // Always clean up immediately regardless of any properties
            tellActor(new MqttDisconnectMsg(DisconnectReasonType.ON_DISCONNECT_MSG, "Client disconnected"));
        } else {
            ctx.close();
        }
    }
```

`channelInactive` retains its specific `try { ... } catch (TbActorNotRegisteredException e)` block — that's distinct error handling, not boilerplate. Leave it as-is.

- [ ] **Step 5: Run the full lightweight test suite**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test
```

Expected: BUILD SUCCESS. All previously-green tests pass.

- [ ] **Step 6: Commit**

```bash
git add lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
git commit -m "$(cat <<'EOF'
[lightweight] T2 refactor: collapse MqttSessionHandler boilerplate

Add tellActor(TbActorMsg) helper that centralizes the session-presence
guard + cached-actor-id tell. Use it from processSubscribe,
processUnsubscribe, processPing, processPublish, processDisconnect, and
the four packet-id-only handlers (PubAck/PubRec/PubRel/PubComp). No
behavior change.
EOF
)"
```

---

## Task 3: Centralize MQTT 5 disconnect-reason mapping (T3)

**Goal:** Move the `DisconnectReasonType → MqttReasonCodes.Disconnect` mapping out of `MqttSessionHandler.disconnect()` into the existing `MqttReasonCodeResolver`.

**Files:**
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/util/MqttReasonCodeResolver.java`
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java`
- Test: `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/util/MqttReasonCodeResolverTest.java` (create if not present)
- Existing integration coverage: `Mqtt5ReasonCodeTest.java` covers the wire-level reason code on broker-initiated DISCONNECT.

- [ ] **Step 1: Write failing unit test for the new resolver method**

Check whether the test class exists:

```bash
ls /home/dlandiak/projects/gsd/tbmq/lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/util/MqttReasonCodeResolverTest.java
```

If it does not exist, create it:

```java
/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.mqtt.broker.lightweight.util;

import io.netty.handler.codec.mqtt.MqttReasonCodes;
import org.junit.jupiter.api.Test;
import org.thingsboard.mqtt.broker.lightweight.session.DisconnectReasonType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MqttReasonCodeResolverTest {

    @Test
    void disconnectReasonForProtocolError() {
        assertEquals(MqttReasonCodes.Disconnect.PROTOCOL_ERROR,
                MqttReasonCodeResolver.disconnectReasonFor(DisconnectReasonType.ON_PROTOCOL_ERROR));
    }

    @Test
    void disconnectReasonForMalformedPacket() {
        assertEquals(MqttReasonCodes.Disconnect.PROTOCOL_ERROR,
                MqttReasonCodeResolver.disconnectReasonFor(DisconnectReasonType.ON_MALFORMED_PACKET));
    }

    @Test
    void disconnectReasonForPacketTooLarge() {
        assertEquals(MqttReasonCodes.Disconnect.PACKET_TOO_LARGE,
                MqttReasonCodeResolver.disconnectReasonFor(DisconnectReasonType.ON_PACKET_TOO_LARGE));
    }

    @Test
    void disconnectReasonForUnspecified() {
        assertEquals(MqttReasonCodes.Disconnect.UNSPECIFIED_ERROR,
                MqttReasonCodeResolver.disconnectReasonFor(DisconnectReasonType.ON_ERROR));
        assertEquals(MqttReasonCodes.Disconnect.UNSPECIFIED_ERROR,
                MqttReasonCodeResolver.disconnectReasonFor(DisconnectReasonType.ON_KEEP_ALIVE));
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails (compile error)**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test -Dtest=MqttReasonCodeResolverTest
```

Expected: COMPILATION ERROR (`disconnectReasonFor` does not exist).

- [ ] **Step 3: Add the new method to `MqttReasonCodeResolver`**

In `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/util/MqttReasonCodeResolver.java`, add an import for `DisconnectReasonType`:

```java
import org.thingsboard.mqtt.broker.lightweight.session.DisconnectReasonType;
```

Append the following method to the class (immediately before the final closing brace, after `disconnectTopicAliasInvalid()` at current line 152):

```java
    /**
     * Maps a {@link DisconnectReasonType} to the appropriate MQTT 5.0 DISCONNECT reason code.
     *
     * <p>Used by transport-layer code when the broker initiates a DISCONNECT to a 5.0 client.
     * Callers must verify the client is MQTT 5.0 before using the returned code.
     *
     * @param reasonType the broker-internal disconnect reason
     * @return the corresponding 5.0 reason code; never {@code null}
     */
    public static MqttReasonCodes.Disconnect disconnectReasonFor(DisconnectReasonType reasonType) {
        return switch (reasonType) {
            case ON_PROTOCOL_ERROR, ON_MALFORMED_PACKET -> MqttReasonCodes.Disconnect.PROTOCOL_ERROR;
            case ON_PACKET_TOO_LARGE -> MqttReasonCodes.Disconnect.PACKET_TOO_LARGE;
            default -> MqttReasonCodes.Disconnect.UNSPECIFIED_ERROR;
        };
    }
```

- [ ] **Step 4: Run the unit test to confirm it passes**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test -Dtest=MqttReasonCodeResolverTest
```

Expected: BUILD SUCCESS, 4 tests pass.

- [ ] **Step 5: Update `MqttSessionHandler.disconnect()` to use the new resolver**

In `MqttSessionHandler.java`, replace the inline `if/else` mapping in `disconnect()` (current lines 400–407) with:

```java
                MqttReasonCodes.Disconnect reasonCode = MqttReasonCodeResolver.disconnectReasonFor(reasonType);
```

After this change, the `disconnect()` method body for the MQTT 5 branch should look like:

```java
            // For MQTT 5.0: send DISCONNECT with reason code before closing (broker-initiated)
            if (sessionCtx.getMqttVersion() == MqttVersion.MQTT_5) {
                MqttReasonCodes.Disconnect reasonCode = MqttReasonCodeResolver.disconnectReasonFor(reasonType);
                try {
                    ctx.writeAndFlush(messageGenerator.createDisconnect(reasonCode));
                } catch (Exception e) {
                    log.debug("[{}] Failed to send DISCONNECT to 5.0 client: {}",
                            sessionCtx.getClientId(), e.getMessage());
                }
            }
```

The previous `if/else` chain (referencing `MqttReasonCodeResolver.disconnectProtocolError()` and `MqttReasonCodes.Disconnect.PACKET_TOO_LARGE` / `UNSPECIFIED_ERROR`) is removed.

- [ ] **Step 6: Run the full lightweight test suite**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test
```

Expected: BUILD SUCCESS. `Mqtt5ReasonCodeTest` (integration) and the new `MqttReasonCodeResolverTest` (unit) both pass.

- [ ] **Step 7: Commit**

```bash
git add lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/util/MqttReasonCodeResolver.java \
        lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java \
        lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/util/MqttReasonCodeResolverTest.java
git commit -m "$(cat <<'EOF'
[lightweight] T3 refactor: centralize disconnect-reason mapping

Move the DisconnectReasonType → MqttReasonCodes.Disconnect switch out
of MqttSessionHandler.disconnect() into a new
MqttReasonCodeResolver.disconnectReasonFor(...) method. Single source
of truth; future reason-code additions land in one place.
EOF
)"
```

---

## Task 4: Logging discipline pass (T4)

**Goal:** Demote `log.warn` / `log.error` sites that fire on benign normal-operation events to `log.debug`. Strict scope: only sites where (a) the path is reachable in normal operation and (b) the demotion is justified by what the message actually says.

**Files (subject to per-site verification before edit):**
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java`
- Read-only review (modify only if a concrete defect is found): `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java`

**Behavior preservation:** Log level changes are not asserted by any test; behavior is identical. Risk is purely cosmetic — operators should see `WARN`/`ERROR` for unusual events, `DEBUG` for noisy normal ones.

- [ ] **Step 1: Survey the candidates**

Run, then review each hit:

```bash
grep -n 'log\.warn\|log\.error' /home/dlandiak/projects/gsd/tbmq/lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
grep -n 'log\.warn\|log\.error' /home/dlandiak/projects/gsd/tbmq/lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/dispatch/DefaultMsgDispatcherService.java
```

For each hit, decide:
- **Keep at WARN/ERROR** if the event indicates an actionable problem (auth failure, protocol violation by client, internal exception).
- **Demote to DEBUG** if the event is reachable in normal operation (network reset, client misbehavior we already handle, race-window-recovered cases).

**Concrete demotion candidate identified during plan-phase reading:**

`MqttSessionHandler.exceptionCaught` (current line 381) logs `error` for every Throwable, including benign `IOException` from a client closing the socket abruptly (very common — every client disconnect that doesn't send DISCONNECT). Demote to `warn` for unexpected exceptions; keep the unexpected ones at WARN level. Actually safer: log at `debug` for connection-reset family (`IOException` and `ClosedChannelException`), keep `warn` (not `error`) for everything else, since the handler is closing the channel anyway and the operator does not need to triage.

- [ ] **Step 2: Demote `exceptionCaught` benign cases**

Replace `MqttSessionHandler.exceptionCaught` (current lines 380–386) with:

```java
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        String clientId = sessionCtx != null ? sessionCtx.getClientId() : "unknown";
        if (cause instanceof java.io.IOException || cause instanceof java.nio.channels.ClosedChannelException) {
            // Common: client closed the socket abruptly. Log at debug — the disconnect is
            // already handled by channelInactive; nothing actionable for operators.
            log.debug("[{}] Channel closed by remote: {}", clientId, cause.getMessage());
        } else {
            log.warn("[{}] Exception in MQTT session handler: {}", clientId, cause.getMessage(), cause);
        }
        disconnect(ctx, DisconnectReasonType.ON_ERROR, cause.getMessage());
    }
```

(Add the imports `java.io.IOException` and `java.nio.channels.ClosedChannelException` if not already present, or use fully-qualified names inline as shown above to avoid import churn.)

- [ ] **Step 3: Review the dispatcher (read-only unless a clear miss is found)**

Open `DefaultMsgDispatcherService.java` and scan for warn/error sites. The recent commit `0029a4377` added drain-on-shutdown logic; treat it as fresh and **do not change** any of its log levels unless one is obviously wrong. Document any concerns in `REVIEW_REPORT.md` as follow-ups.

If — and only if — a concrete defect is observed (e.g., `log.warn` on every dispatch when the queue is empty during normal startup), apply the smallest demotion. Otherwise, do not modify the file.

- [ ] **Step 4: Run the full lightweight test suite**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test
```

Expected: BUILD SUCCESS. No tests assert log levels.

- [ ] **Step 5: Commit**

```bash
git add lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java
git commit -m "$(cat <<'EOF'
[lightweight] T4 chore: logging discipline in MqttSessionHandler

Demote remote-close exceptions (IOException / ClosedChannelException)
from ERROR to DEBUG in exceptionCaught — these are routine and already
handled by channelInactive; nothing actionable for operators. Other
exceptions remain at WARN with a stack trace.
EOF
)"
```

---

## Task 5: Introduce `ProtocolViolationException` (T7, must precede Task 6)

**Goal:** Add a typed exception for MQTT protocol violations, mirroring main TBMQ. Used by Task 6 in place of generic `RuntimeException` in `TopicAliasCtx`.

**Files:**
- Create: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/exception/ProtocolViolationException.java`
- Test: no new test needed — the class is a value carrier with no logic. Task 6 covers it via `TopicAliasCtx` tests.

- [ ] **Step 1: Create the exception class**

Write `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/exception/ProtocolViolationException.java` with this exact content:

```java
/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.mqtt.broker.lightweight.exception;

import java.io.Serial;

/**
 * Thrown when an MQTT client violates the protocol (malformed packet, invalid topic alias,
 * out-of-spec property value, etc.). Caught at the transport boundary and translated into
 * a broker-initiated DISCONNECT with the appropriate reason code.
 */
public class ProtocolViolationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 6130139932588069150L;

    public ProtocolViolationException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Verify the file compiles**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Run the full lightweight test suite (sanity check — no behavior changed)**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/exception/ProtocolViolationException.java
git commit -m "$(cat <<'EOF'
[lightweight] T7 add: ProtocolViolationException

Typed unchecked exception for MQTT protocol violations. Will be wired
into TopicAliasCtx in the next commit (T6) to replace a generic
RuntimeException-as-control-flow on the inbound publish hot path.
EOF
)"
```

---

## Task 6: Replace `TopicAliasCtx` `RuntimeException` with `ProtocolViolationException` (T6)

**Goal:** Stop using generic `RuntimeException` for protocol-level violations in the topic-alias path. Use `ProtocolViolationException` (added in Task 5) so the catch site in `MqttSessionHandler.processPublish` is type-narrow and the policy is self-documenting.

**Files:**
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtx.java`
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java`
- Test: existing `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/Mqtt5TopicAliasTest.java` — must remain green (covers the externally-observable DISCONNECT-with-reason-code).
- Test (new, optional unit): `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtxTest.java` — add only if the file does not already exist.

**Behavior preservation:** The wire-level outcome (broker sends DISCONNECT with `TOPIC_ALIAS_INVALID`/`PROTOCOL_ERROR` reason and closes the channel) is unchanged. Only the JVM exception type changes from `RuntimeException` to `ProtocolViolationException`.

- [ ] **Step 1: Check whether a unit-test class already exists; create skeleton if not**

```bash
ls /home/dlandiak/projects/gsd/tbmq/lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtxTest.java 2>&1
```

If it does not exist, create it with this content:

```java
/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.mqtt.broker.lightweight.session;

import org.junit.jupiter.api.Test;
import org.thingsboard.mqtt.broker.lightweight.exception.ProtocolViolationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopicAliasCtxTest {

    @Test
    void zeroAliasReturnsNullWithoutValidation() {
        TopicAliasCtx ctx = new TopicAliasCtx(10, 10);
        assertNull(ctx.getTopicNameByAlias("topic/a", 0));
    }

    @Test
    void firstUseStoresMappingAndReturnsTopicName() {
        TopicAliasCtx ctx = new TopicAliasCtx(10, 10);
        assertEquals("topic/a", ctx.getTopicNameByAlias("topic/a", 1));
        // Subsequent empty topic name resolves via the stored mapping
        assertEquals("topic/a", ctx.getTopicNameByAlias("", 1));
    }

    @Test
    void aliasZeroIsProtocolViolation() {
        TopicAliasCtx ctx = new TopicAliasCtx(10, 10);
        assertThrows(ProtocolViolationException.class, () -> ctx.validateInboundAlias(0));
    }

    @Test
    void aliasExceedingInboundMaxIsProtocolViolation() {
        TopicAliasCtx ctx = new TopicAliasCtx(5, 5);
        assertThrows(ProtocolViolationException.class, () -> ctx.validateInboundAlias(6));
    }

    @Test
    void disabledContextRejectsAnyAliasAsProtocolViolation() {
        TopicAliasCtx ctx = TopicAliasCtx.DISABLED_TOPIC_ALIASES;
        assertThrows(ProtocolViolationException.class, () -> ctx.validateInboundAlias(1));
    }

    @Test
    void unknownAliasOnEmptyTopicIsProtocolViolation() {
        TopicAliasCtx ctx = new TopicAliasCtx(10, 10);
        assertThrows(ProtocolViolationException.class, () -> ctx.getTopicNameByAlias("", 5));
    }

    @Test
    void validResolvedMappingDoesNotThrow() {
        TopicAliasCtx ctx = new TopicAliasCtx(10, 10);
        ctx.getTopicNameByAlias("foo", 1);
        assertDoesNotThrow(() -> ctx.getTopicNameByAlias("", 1));
    }
}
```

- [ ] **Step 2: Run the new tests to confirm they fail**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test -Dtest=TopicAliasCtxTest
```

Expected: tests fail with `RuntimeException is not assignable to ProtocolViolationException` (or similar). If the test class did not need creating because it already existed, validate the `ProtocolViolationException` cases fail, then proceed.

- [ ] **Step 3: Replace `RuntimeException` with `ProtocolViolationException` in `TopicAliasCtx`**

In `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtx.java`, add the import:

```java
import org.thingsboard.mqtt.broker.lightweight.exception.ProtocolViolationException;
```

Replace the three `throw new RuntimeException(...)` calls in `validateInboundAlias` (current lines 177, 181, 184) and the one in `getTopicNameByAlias` (current line 126) with `throw new ProtocolViolationException(...)`. The Javadoc `@throws RuntimeException` clauses on lines 109 and 175 should be updated to `@throws ProtocolViolationException`.

- [ ] **Step 4: Update the catch site in `MqttSessionHandler.processPublish`**

In `MqttSessionHandler.java`, narrow the catch in `processPublish` (current lines 205–210) from `RuntimeException` to `ProtocolViolationException`:

```java
                } catch (org.thingsboard.mqtt.broker.lightweight.exception.ProtocolViolationException e) {
                    // Topic alias validation failed (alias=0, exceeds max, or unknown mapping)
                    log.warn("[{}] Invalid topic alias: {}", sessionCtx.getClientId(), e.getMessage());
                    disconnect(ctx, DisconnectReasonType.ON_PROTOCOL_ERROR, e.getMessage());
                    return;
                }
```

(Or add an import for `ProtocolViolationException` and use the short name — choose whichever the project's convention favors; either is correct.)

- [ ] **Step 5: Run the unit tests; confirm pass**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test -Dtest=TopicAliasCtxTest
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Run the integration test that exercises the wire-level behavior**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test -Dtest=Mqtt5TopicAliasTest
```

Expected: BUILD SUCCESS. Wire-level DISCONNECT-with-reason-code unchanged.

- [ ] **Step 7: Run the full lightweight test suite**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test
```

Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtx.java \
        lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttSessionHandler.java \
        lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/session/TopicAliasCtxTest.java
git commit -m "$(cat <<'EOF'
[lightweight] T6 refactor: typed exception for topic-alias violations

Replace generic RuntimeException with the typed
ProtocolViolationException in TopicAliasCtx; narrow the catch site in
MqttSessionHandler.processPublish accordingly. Wire-level behavior
(broker DISCONNECT with PROTOCOL_ERROR reason) unchanged.
EOF
)"
```

---

## Task 7: `LastWillService` lifecycle + `mqtt.lwt.fired.total` counter (T8)

**Goal:** Add a `@PreDestroy` to clear the in-memory will map on shutdown, and a Micrometer counter that increments when an LWT actually publishes.

**Files:**
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/will/DefaultLastWillService.java`
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsService.java`
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java`
- Test: existing `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttLwtIntegrationTest.java` — extend with one assertion that the counter increments.

**Public-surface impact:** Adds a new Prometheus counter `mqtt.lwt.fired.total`. This *adds* an observability surface; it does not rename or remove anything. Considered safe under the spec's "no public configuration property renamed/removed" rule.

- [ ] **Step 1: Register the counter in `BrokerMetricsService`**

In `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsService.java`, append a registration to `init()` (current line 51):

```java
        // Register LWT-fired counter — incremented in ClientActor when a Last Will is delivered
        Counter.builder("mqtt.lwt.fired.total")
                .description("Total Last Will Testament messages fired (delivered to subscribers)")
                .register(meterRegistry);
```

- [ ] **Step 2: Add `@PreDestroy` to `DefaultLastWillService`**

In `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/will/DefaultLastWillService.java`, add the import:

```java
import jakarta.annotation.PreDestroy;
```

Append the destroy method to the class (immediately before the final closing brace, after `removeWillWithoutDelivery` at current line 61):

```java
    @PreDestroy
    public void destroy() {
        int remaining = willMessages.size();
        willMessages.clear();
        if (remaining > 0) {
            log.info("Cleared {} pending LWT entries on shutdown", remaining);
        }
    }
```

- [ ] **Step 3: Increment the counter in `ClientActor` at the LWT delivery site**

In `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java`, locate `processDisconnect` and the LWT delivery block (current lines 289–311). Add the counter increment immediately after `log.debug("[{}] Delivering LWT...")` at line 292:

Before:

```java
            lastWillService.removeWill(sessionCtx.getSessionId()).ifPresent(will -> {
                log.debug("[{}] Delivering LWT on topic '{}' (reason: {})", clientId, will.getTopicName(), reasonType);
                // Handle retain flag on LWT
                if (will.isRetain()) {
```

After:

```java
            lastWillService.removeWill(sessionCtx.getSessionId()).ifPresent(will -> {
                log.debug("[{}] Delivering LWT on topic '{}' (reason: {})", clientId, will.getTopicName(), reasonType);
                meterRegistry.counter("mqtt.lwt.fired.total").increment();
                // Handle retain flag on LWT
                if (will.isRetain()) {
```

- [ ] **Step 4: Extend the existing LWT integration test with a counter assertion**

Open `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttLwtIntegrationTest.java`. Locate one test that exercises a successful LWT delivery (the test that asserts a subscriber receives the Will). Add a Micrometer assertion. Sketch — adapt to the existing test class's autowiring style:

```java
    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    // Inside a test that fires LWT on ungraceful disconnect:
    double before = meterRegistry.counter("mqtt.lwt.fired.total").count();
    // ... existing LWT-firing test logic ...
    org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(5))
            .until(() -> meterRegistry.counter("mqtt.lwt.fired.total").count() > before);
```

If the existing test does not already autowire `MeterRegistry`, add the field. If multiple tests in the class fire LWT in sequence (and run in the same Spring context), use a "before" baseline rather than a hardcoded value to keep tests order-independent.

- [ ] **Step 5: Run the LWT integration test**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test -Dtest=MqttLwtIntegrationTest
```

Expected: BUILD SUCCESS, all LWT tests pass, and the counter assertion verifies the increment.

- [ ] **Step 6: Run the full lightweight test suite**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/metrics/BrokerMetricsService.java \
        lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/service/mqtt/will/DefaultLastWillService.java \
        lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java \
        lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttLwtIntegrationTest.java
git commit -m "$(cat <<'EOF'
[lightweight] T8 obs: LWT lifecycle hook + mqtt.lwt.fired.total counter

Add @PreDestroy to DefaultLastWillService to clear the in-memory map
on shutdown (defensive; not a defect today). Register a new
Micrometer counter mqtt.lwt.fired.total in BrokerMetricsService and
increment it from ClientActor.processDisconnect when a LWT is
actually delivered. Extend MqttLwtIntegrationTest with a counter
assertion.
EOF
)"
```

---

## Task 8: `MqttPropertiesUtil` correctness sweep (T9)

**Goal:** Compare `lightweight/util/MqttPropertiesUtil.java` against `application/util/MqttPropertiesUtil.java` (read-only) and pull in any concrete spec-correctness fixes. Reject anything tied to clustering or persistence.

**Files (subject to findings):**
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/util/MqttPropertiesUtil.java`
- Test: add unit tests for any newly added validation in `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/util/MqttPropertiesUtilTest.java` (create if not present).

**Plan-phase pre-read finding:** A side-by-side scan of the two files showed no concrete spec-correctness gap in lightweight's util. Lightweight handles `null`/`NO_PROPERTIES` defensively, returns sensible defaults, and covers extraction + addition of every property used in R1. Main has additional helpers (`getTopicAliasProperty`, `getResponseInfo`) that are *callers* — not validation. Most likely outcome: this task is **rejected with reasoning** in the report.

This task therefore proceeds in two stages: a verification step that may produce zero changes, and a guarded application step.

- [ ] **Step 1: Run a precise diff**

```bash
diff -u /home/dlandiak/projects/gsd/tbmq/application/src/main/java/org/thingsboard/mqtt/broker/util/MqttPropertiesUtil.java \
        /home/dlandiak/projects/gsd/tbmq/lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/util/MqttPropertiesUtil.java | less
```

For every difference, classify as one of:
- **Spec-correctness fix** — main does additional validation that prevents an undefined-behavior path. Apply.
- **Caller helper** — a method that simplifies a *caller* but adds no correctness. Skip; lightweight's callers don't need it.
- **Persistence / clustering** — methods tied to PublishMsgProto / DevicePublishMsg / Kafka. Reject.
- **Cosmetic** — formatting, ordering, naming. Skip per spec.

- [ ] **Step 2a: If no spec-correctness gap is found, document and skip the code change**

Append a concise note to a working buffer for the eventual `REVIEW_REPORT.md` (the buffer is a scratch in-session file at `/tmp/lightweight-review-notes.md`; the report is assembled in Task 11). Note format:

```markdown
### T9 (MqttPropertiesUtil sweep) — REJECTED

Plan-phase diff against main showed only caller-helper additions
(getTopicAliasProperty, getResponseInfo) and persistence-coupled
methods (PublishMsgProto-aware paths). No spec-correctness gap. No
change applied.
```

Then jump to Task 9.

- [ ] **Step 2b: If a spec-correctness gap is found, write a failing test first**

Create or extend `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/util/MqttPropertiesUtilTest.java`. Each gap gets one test that fails with the current implementation and passes with the fix. Show the exact test code:

```java
package org.thingsboard.mqtt.broker.lightweight.util;

import io.netty.handler.codec.mqtt.MqttProperties;
import org.junit.jupiter.api.Test;

// (Add tests as gaps are found; concrete bodies depend on findings.)
```

- [ ] **Step 3b: Apply the smallest fix to `MqttPropertiesUtil` to make tests pass**

Pull the validation logic from main verbatim where it cleanly applies. Do NOT pull persistence-coupled overloads.

- [ ] **Step 4: Run the full lightweight test suite**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit (only if changes were applied; otherwise skip the commit)**

```bash
git add lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/util/MqttPropertiesUtil.java \
        lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/util/MqttPropertiesUtilTest.java
git commit -m "$(cat <<'EOF'
[lightweight] T9 fix: MqttPropertiesUtil spec-correctness alignment

Pull <specific change> from main TBMQ to close <specific gap>. See
REVIEW_REPORT.md T9 entry for details.
EOF
)"
```

If no commit is created, document the rejection in the report (Task 11) and proceed to Task 9.

---

## Task 9: Fix `ClientActor` displaced-session race in takeover (T10)

**Goal:** Eliminate the race window in `ClientActor.processConnect` takeover where Netty's `channelInactive` on the closed-old channel can fire while `oldSession.state == DISCONNECTING`, sending a `SessionCloseMsg` that the (reused) actor processes against the *new* `sessionCtx`.

**Files:**
- Modify: `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java`
- Test: extend `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttClientTakeoverTest.java` with a regression test.

**Verified race (plan-phase reading):**

```text
Old TCP connection (sessionCtx_OLD) — actor instance A, sessionCtx field = OLD
  ↓ takeover from new TCP connection arrives
processConnect (ClientActor) on actor thread:
  L215  oldSession.setState(DISCONNECTING)        // OLD state observable as DISCONNECTING
  L216  if (oldSession.getChannel().channel().isActive())
  L217      oldSession.getChannel().close()        // ASYNC — schedules close on Netty event loop
  L219  oldSession.setState(DISCONNECTED)         // <-- happens after close is scheduled

Netty event loop later: channel close completes → fires channelInactive on OLD handler
OLD MqttSessionHandler.channelInactive:
  L367  if (sessionCtx != null && sessionCtx.getState() != SessionState.DISCONNECTED)
            // ^ may still see DISCONNECTING if the actor thread hasn't reached L219
  L370      actorSystem.tell(actorId, new SessionCloseMsg(ON_CHANNEL_CLOSED))

Actor instance A receives SessionCloseMsg, but its sessionCtx field is now NEW (overwritten by
processSessionInit before the new processConnect ran). processDisconnect fires against the NEW
session. Result: brand-new client connection torn down with reason ON_CHANNEL_CLOSED.
```

**Fix:** set `oldSession.state = DISCONNECTED` *before* calling `close()`. Volatile write happens-before the channel close call; channelInactive (which fires from the Netty event loop *after* the close it observes) will always see `DISCONNECTED` and skip the tell. The intermediate `DISCONNECTING` state in the takeover path is then redundant and is dropped.

**Behavior preservation note:** Other (non-takeover) paths still use `setState(DISCONNECTING)` — `processDisconnect` at line 286, and `MqttSessionHandler.disconnect` at line 396 — those are not changed.

- [ ] **Step 1: Write a failing regression test**

In `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttClientTakeoverTest.java`, add a new test method. The test stresses the takeover path enough that the race window is reliably exposed without the fix. The strategy: open and close TCP connections rapidly under the same `clientId` and assert that the most recent (newest) connection survives and remains operational.

```java
    @org.junit.jupiter.api.Test
    void newConnectionSurvivesRapidTakeoverRace() throws Exception {
        // Stress-fire 20 takeovers in rapid succession on the same clientId.
        // Without the fix, the displaced-session race window may close the new connection
        // (the OLD handler's channelInactive sends SessionCloseMsg under stale state).
        String clientId = "takeover-race-" + java.util.UUID.randomUUID();

        org.eclipse.paho.client.mqttv3.MqttClient lastClient = null;
        for (int i = 0; i < 20; i++) {
            org.eclipse.paho.client.mqttv3.MqttClient c = createClient(clientId);
            c.connect(defaultConnectOptions());
            // Do NOT disconnect — the next iteration is a TCP-level takeover.
            lastClient = c;
        }

        // Assert the final client is still connected and can perform a round-trip.
        org.junit.jupiter.api.Assertions.assertNotNull(lastClient);
        org.junit.jupiter.api.Assertions.assertTrue(lastClient.isConnected(),
                "Most-recent client should remain connected after rapid takeover storm");

        // Round-trip: subscribe + publish to itself, expect delivery.
        java.util.concurrent.CountDownLatch deliveryLatch = new java.util.concurrent.CountDownLatch(1);
        lastClient.subscribe("takeover/race/" + clientId, (topic, message) -> deliveryLatch.countDown());
        lastClient.publish("takeover/race/" + clientId, "hello".getBytes(), 1, false);
        org.junit.jupiter.api.Assertions.assertTrue(
                deliveryLatch.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "Final client should remain operational after takeover race");
    }
```

(`createClient` and `defaultConnectOptions` come from `AbstractMqttIntegrationTest`. If `MqttClientTakeoverTest` does not already extend that base class, follow the existing test's pattern instead.)

- [ ] **Step 2: Run the new test to confirm it fails** (or is flaky — both are evidence of the race)

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test \
    -Dtest=MqttClientTakeoverTest#newConnectionSurvivesRapidTakeoverRace
```

Expected: FAIL or FLAKE. If the test passes consistently across 5 runs (`for i in 1 2 3 4 5; do ...; done`), the race window is too narrow to reproduce in CI; document this in the commit and proceed with the fix anyway — the static analysis above is sufficient justification.

- [ ] **Step 3: Apply the fix in `ClientActor.processConnect`**

In `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java`, replace the takeover block (current lines 209–219) with:

Before:

```java
            // Clear old session's QoS state and release packet IDs to avoid resource leak
            oldSession.getInboundQos2().clear();
            oldSession.getOutboundQos1().clear();
            oldSession.getOutboundQos2().clear();
            oldSession.getPacketIdAllocator().releaseAll();
            // Close the old session's channel
            oldSession.setState(SessionState.DISCONNECTING);
            if (oldSession.getChannel().channel().isActive()) {
                oldSession.getChannel().close();
            }
            oldSession.setState(SessionState.DISCONNECTED);
```

After:

```java
            // Clear old session's QoS state and release packet IDs to avoid resource leak
            oldSession.getInboundQos2().clear();
            oldSession.getOutboundQos1().clear();
            oldSession.getOutboundQos2().clear();
            oldSession.getPacketIdAllocator().releaseAll();
            // Mark the old session DISCONNECTED *before* closing its channel.
            // channelInactive on the old handler fires from the Netty event loop AFTER
            // the close call returns; the volatile write below happens-before that close,
            // so channelInactive will observe DISCONNECTED and skip its SessionCloseMsg
            // tell — preventing it from tearing down the new session via the reused
            // ClientActor (whose sessionCtx field has already been swapped to the new one).
            oldSession.setState(SessionState.DISCONNECTED);
            if (oldSession.getChannel().channel().isActive()) {
                oldSession.getChannel().close();
            }
```

- [ ] **Step 4: Run the regression test; confirm it now passes**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test \
    -Dtest=MqttClientTakeoverTest#newConnectionSurvivesRapidTakeoverRace
```

Expected: PASS.

Run the full takeover suite to confirm nothing else regressed:

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test -Dtest=MqttClientTakeoverTest
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Run the full lightweight test suite**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java \
        lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttClientTakeoverTest.java
git commit -m "$(cat <<'EOF'
[lightweight] T10 fix: close displaced-session race in takeover

Mark oldSession DISCONNECTED before scheduling the channel close so
channelInactive on the old handler always observes DISCONNECTED and
skips its SessionCloseMsg tell. Without this, a rapid takeover could
race the OLD handler's channelInactive against the actor thread's
DISCONNECTING→DISCONNECTED transition, routing a SessionCloseMsg to
the (reused) ClientActor whose sessionCtx field had already been
swapped to the NEW session — incorrectly tearing down the brand-new
connection. Add a takeover-storm regression test.
EOF
)"
```

---

## Task 10: Idiomatic polish bounded to files touched by Tasks 1–9 (T5)

**Goal:** Targeted micro-improvements limited to files already modified in this plan. No driveby refactors. No structural change.

**Allowed scope (per spec §4.T5):**
- Lombok consistency in service impls touched by previous tasks.
- `switch` block → `switch` expression where it strictly simplifies and the variable is used afterwards.
- Residual field injection in touched files → `@RequiredArgsConstructor` + `private final` (only if the existing class has no Spring-circular-bean concern; verify).
- Trivial `var` substitutions where `var` removes a verbose declared type (e.g., `Map<UUID, WillMessage> map = new ConcurrentHashMap<>();` → keep — explicit type is fine; do not change pure aesthetic cases).
- Removal of dead imports.

**Forbidden:**
- Touching any file *not* modified by Tasks 1–9.
- Renaming public methods, fields, or types.
- Reordering class members for cosmetic reasons.

**Files in scope (only modify these):**
- `MqttSessionHandler.java`
- `ClientSessionCtx.java`
- `MqttReasonCodeResolver.java`
- `TopicAliasCtx.java`
- `DefaultLastWillService.java`
- `BrokerMetricsService.java`
- `ClientActor.java`
- (`MqttPropertiesUtil.java` only if Task 8 modified it)

- [ ] **Step 1: Survey for dead imports across the in-scope files**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o compile -X 2>&1 | grep -i "unused import" | head -20
```

(If your IDE/linter is configured, that's faster.) Remove any dead imports introduced by Tasks 1–9.

- [ ] **Step 2: Apply each candidate idiomatic improvement, one by one**

For each candidate:
1. Make the change in one file.
2. Run `mvn -f ... -o test`.
3. If green, leave it. If red or marginal, revert.

Do **not** batch multiple polish edits and commit them as a unit — each should be defensible on its own. If two or more changes are too small to commit individually, group them in this single Task 10 commit but list every change in the commit message.

- [ ] **Step 3: Run the full lightweight test suite**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o test
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add <only files actually changed>
git commit -m "$(cat <<'EOF'
[lightweight] T5 chore: idiomatic polish in files touched by R1 review

Bounded cleanup limited to files modified earlier in this review pass:
- <list each change here>
No behavior change; existing tests cover all touched paths.
EOF
)"
```

If no polish changes were warranted, skip the commit and document in `REVIEW_REPORT.md` that T5 produced no diff.

---

## Task 11: Validate, smoke test, and write `REVIEW_REPORT.md`

**Goal:** Final validation, manual smoke run, and the deliverable report.

**Files:**
- Create: `REVIEW_REPORT.md` at the repository root, OR at `lightweight/REVIEW_REPORT.md` (place wherever the repository's PR-attached reports live; default to `lightweight/REVIEW_REPORT.md` since the scope is lightweight).

- [ ] **Step 1: Run the full lightweight test suite**

```bash
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o verify
```

Expected: BUILD SUCCESS. All tests green. Capture the full test count from the surefire summary line (e.g., `Tests run: 154, Failures: 0, Errors: 0, Skipped: 0`).

- [ ] **Step 2: Build the Docker image and run a smoke test**

Build the image:

```bash
cd /home/dlandiak/projects/gsd/tbmq/lightweight
mvn -o package
docker build -t tbmq-lightweight-review:smoke -f docker/Dockerfile .
```

Run the broker:

```bash
docker run --rm -d --name tbmq-smoke -p 1883:1883 -p 8083:8083 tbmq-lightweight-review:smoke
sleep 5
docker logs tbmq-smoke | tail -50
```

Run a basic Paho round-trip from the host (or from another container — choose what's available). A minimal CLI: `mosquitto_pub`/`mosquitto_sub` if installed, or a tiny Java/Python script. Goal: connect → subscribe → publish → receive → disconnect, all without error.

If no MQTT CLI is available locally, run the existing `MqttQosIntegrationTest` against the running container by overriding `tbmq.netty.port=1883` and pointing the test base class at the host port. Or simpler: use `docker exec tbmq-smoke /bin/bash` and run a one-shot Java/Python publish-subscribe.

Stop the container:

```bash
docker stop tbmq-smoke
```

If smoke fails, root-cause and either fix or document; do not skip silently.

- [ ] **Step 3: Write `lightweight/REVIEW_REPORT.md`**

Use this exact skeleton; fill in each section:

```markdown
# TBMQ Lightweight R1 Code Review — Report

**Date:** 2026-05-04
**Branch:** <branch name>
**Spec:** `docs/superpowers/specs/2026-05-04-tbmq-lightweight-r1-review-design.md`
**Plan:** `docs/superpowers/plans/2026-05-04-tbmq-lightweight-r1-review.md`
**Profile:** B (surgical + idiomatic polish)

## Executive summary

(3–5 sentences. What changed; why; net impact on correctness, observability, and code health.)

## Per-group findings

### T1 — Cache TbTypeActorId per session
- **Changed:** ClientSessionCtx.java, MqttSessionHandler.java
- **Source pattern in main:** `application/src/main/java/org/thingsboard/mqtt/broker/server/MqttSessionHandler.java`
- **Test impact:** No new tests; existing protocol/QoS tests cover all reused-actorId call sites.
- **Net:** -10 allocations per inbound MQTT packet across PUBLISH/PUBACK/PUBREC/PUBREL/PUBCOMP/SUBSCRIBE/UNSUBSCRIBE/PINGREQ/DISCONNECT plus channelInactive.

### T2 — Collapse MqttSessionHandler boilerplate
(...)

### T3 — Centralize disconnect-reason mapping
(...)

### T4 — Logging discipline
(...)

### T5 — ProtocolViolationException added
(...)

### T6 — TopicAliasCtx uses ProtocolViolationException
(...)

### T8 — LastWillService observability + lifecycle
(...)

### T9 — MqttPropertiesUtil sweep
(state explicitly: applied / rejected with reasoning)

### T10 — ClientActor displaced-session race fix
(... include the race trace from this plan, the fix, and the regression test name)

### T5-polish — Idiomatic polish
(... list each touched file and what changed, or "no diff applied")

## Items considered and rejected

### T11 — TbActorMailbox concurrency review (REJECTED)

Plan-phase reading found the `ConcurrentLinkedQueue + AtomicBoolean`
pattern with explicit `busy`/`ready`/`destroyInProgress` flags to be
sound. The `forEach` (rather than `drain`) in `destroy()` is benign
given `ready=NOT_READY` blocks future `processMailbox` runs. No
benchmark or profiler signal exists to justify a change.

### Out-of-scope items not pulled from main

- RateLimitService / Bucket4j (not R1)
- TbMessageStatsReportClient / historical stats reporter (clustering)
- ClientSubscriptionPersistenceService / Kafka listeners (clustering)
- StatsManager / multi-layer metrics (over-engineered for single node)
- SCRAM enhanced auth (not R1)
- AbstractMqttChannelInitializer / AbstractMqttHandlerProvider abstraction (premature)

## Lightweight is better than main — backport candidates

- `PingMsg.INSTANCE` singleton (no allocation per PINGREQ)
- `PacketIdAllocator` as its own class (cleaner than embedded `MsgIdSequence`)
- LWT keyed by session UUID (correct takeover isolation)
- Constructor injection consistently via `@RequiredArgsConstructor`
- No clustering coupling at all

## Test results

- `mvn -f lightweight/pom.xml -o verify` — Tests run: <N>, Failures: 0, Errors: 0, Skipped: <S>
- New unit tests: <list>
- Extended integration tests: `MqttLwtIntegrationTest` (counter assertion), `MqttClientTakeoverTest` (race regression)
- Smoke test (docker run + round-trip): PASS

## Open questions / R2 follow-ups

- ARM64 hardware validation (Phase 7 SC-3) — pre-existing item, not part of this pass
- 24-hour soak run — closed as OPTIONAL per Phase 7 status
- Phase 4 HUMAN-UAT (filesystem-path TLS, credential persistence-across-restart) — pre-existing item
- T11 (mailbox) — re-review only if a benchmark surfaces a concrete contention or shutdown loss issue
```

- [ ] **Step 4: Commit the report**

```bash
git add lightweight/REVIEW_REPORT.md
git commit -m "$(cat <<'EOF'
[lightweight] docs: R1 code review report

Summarizes T1–T10 changes, items considered and rejected (T11), main
TBMQ patterns explicitly out of scope, lightweight-better-than-main
backport candidates, full test results, and R2 follow-ups.
EOF
)"
```

- [ ] **Step 5: Final verification**

```bash
git log --oneline | head -15
mvn -f /home/dlandiak/projects/gsd/tbmq/lightweight/pom.xml -o verify
```

Expected: ~10 new commits on the branch, each prefixed `[lightweight] T<n>`. Final test run green.

---

## Out-of-plan reminders

- Each task commits independently. Do not batch.
- If during execution a task turns out to be a bad idea, **skip it** and document the reason in `REVIEW_REPORT.md`. Do not invent new tasks not in this plan; surface them as follow-ups.
- Do not touch any file outside `lightweight/` (and `docs/superpowers/{specs,plans}/` for plan-related docs already committed).
- Do not modify `lightweight/pom.xml`, `lightweight/docker/Dockerfile`, `lightweight/src/main/resources/tbmq-lightweight.yml`, or any other public-surface file unless the change is in the approved task list (it isn't, for this plan).
- The dispatcher (`DefaultMsgDispatcherService`) was just patched in `0029a4377`. Treat as fragile. Only modify if Task 4 finds a concrete log-level miss; otherwise leave alone.
- If a test you didn't change fails, that's a flake or a real prior break — do not silently @Disabled it. Investigate.
