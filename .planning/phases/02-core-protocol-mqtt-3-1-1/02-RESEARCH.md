# Phase 2: Core Protocol (MQTT 3.1.1) - Research

**Researched:** 2026-04-06
**Domain:** MQTT 3.1.1 protocol engine — Netty codec, actor-per-client session model, QoS 0/1/2 state machines, retained messages, LWT, keep-alive enforcement, client takeover
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Actor-per-client model — each client ID gets a dedicated actor with a mailbox queue guaranteeing sequential per-client message processing.
- **D-02:** Port TBMQ's `common/actor` framework (TbActorSystem, TbActorMailbox, AbstractTbActor, TbActorRef, TbActorCreator) into the lightweight project. Battle-tested implementation preferred over custom.
- **D-03:** Single dispatcher (ForkJoinPool) is sufficient for single-node — no multi-dispatcher complexity.
- **D-04:** Phase 2 uses direct inline delivery — publisher's actor looks up matching subscriptions from the registry and writes directly to each subscriber's channel (`channel.writeAndFlush()`). No dispatch queue or intermediary service.
- **D-05:** Phase 3 refactors inline delivery into an abstracted dispatch service with LinkedBlockingQueue behind PublishMsgQueueFactory interface.
- **D-06:** Subscription registry uses `ConcurrentHashMap<String, Set<Subscription>>` for exact topic matching. Phase 3 replaces with the concurrent wildcard subscription trie.
- **D-07:** Wildcard subscriptions (`+`/`#`) are accepted and stored (valid SUBACK return codes), but only exact topic matches trigger delivery in Phase 2.
- **D-08:** Spec-complete MQTT 3.1.1 — all MUST/SHOULD covered: malformed packet detection, correct CONNACK codes, packet ID tracking (1-65535), DUP flag handling, max client ID length (23 chars, configurable), empty client ID with Clean Session=1, protocol violation disconnect, configurable max payload size, `$SYS` topic behavior.
- **D-09:** Retained messages stored in-memory only (`ConcurrentHashMap<String, RetainedMessage>`). No persistence across restarts.
- **D-10:** Clean Session=0 connects are accepted but behave as Clean Session=1 (no session restoration). Persistent sessions deferred to R2.

### Claude's Discretion

- Exact class names and package layout for protocol handlers (follow TBMQ naming conventions per D-03 from Phase 1)
- QoS 2 state machine implementation details (in-flight message tracking data structures)
- Packet ID allocator implementation (1-65535 range with proper reuse)
- LWT storage and delivery mechanism internals
- Keep-alive timer implementation approach (IdleStateHandler configuration)
- Client takeover coordination internals
- MQTT message builder/generator utility structure

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope.

</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PROTO-01 | Broker accepts MQTT 3.1.1 CONNECT packets and completes connection handshake with CONNACK | Netty MqttDecoder/MqttEncoder + MqttSessionHandler pattern verified from TBMQ source |
| PROTO-02 | Broker handles PUBLISH/PUBACK/PUBREC/PUBREL/PUBCOMP for QoS 0, 1, and 2 message flows | QoS state machine patterns from TBMQ MqttMessageHandler; in-flight map per session |
| PROTO-03 | Broker processes SUBSCRIBE/SUBACK and UNSUBSCRIBE/UNSUBACK with correct return codes | ConcurrentHashMap subscription registry + Netty MqttMessageBuilders for SUBACK |
| PROTO-04 | Broker responds to PINGREQ with PINGRESP and disconnects clients exceeding keep-alive timeout | IdleStateHandler dynamic reconfiguration + userEventTriggered for IdleStateEvent |
| PROTO-05 | Broker stores retained messages in memory and delivers them to new subscribers on matching topics | ConcurrentHashMap<String, RetainedMessage> + delivery-on-subscribe pattern |
| PROTO-06 | Broker publishes Last Will and Testament message on ungraceful disconnect or keep-alive expiry | DisconnectReasonType.allowsLastWillOnDisconnect() pattern + LWT service |
| PROTO-07 | Broker supports Clean Session flag — sessions are in-memory only, not persisted across restarts | All session state in-actor; Clean Session=0 treated as Clean Session=1 in R1 |
| PROTO-11 | Broker correctly handles client takeover (new connection with same client ID replaces existing session) including LWT behavior | Actor registry ConcurrentHashMap; old actor displaced via TAKEOVER disconnect reason |
| TRAN-01 | Broker listens on configurable TCP port (default 1883) for plain MQTT connections | Already wired in Phase 1 MqttTcpServerBootstrap; pipeline extension only |

</phase_requirements>

---

## Summary

Phase 2 implements a fully spec-compliant MQTT 3.1.1 broker by extending the Netty TCP server from Phase 1 with the MQTT codec pipeline (MqttDecoder/MqttEncoder), a per-channel MqttSessionHandler, and a per-client actor system ported from `common/actor`. All MQTT packet types — CONNECT/CONNACK, PUBLISH/PUBACK/PUBREC/PUBREL/PUBCOMP, SUBSCRIBE/SUBACK, UNSUBSCRIBE/UNSUBACK, PINGREQ/PINGRESP, DISCONNECT — are handled at this layer. The actor-per-client model serializes all session processing so QoS state machines, retained message delivery, LWT, and client takeover logic can be implemented without locks inside the actor.

The key architectural insight is that Phase 2 deliberately uses the simplest viable implementations for each component: `ConcurrentHashMap` for subscriptions and retained messages (exact-match only), direct `channel.writeAndFlush()` for delivery (no queue), and a single ForkJoinPool dispatcher. These are designed for correctness and test coverage, not throughput — Phase 3 replaces the hot paths with abstractions and the wildcard subscription trie.

The most implementation-sensitive areas are: (1) dynamic keep-alive reconfiguration of `IdleStateHandler` per client, which requires replacing the handler in-pipeline after CONNECT; (2) client takeover LWT suppression — the disconnect reason for the displaced session must be `ON_CONFLICTING_SESSIONS` so the LWT service skips delivery; (3) QoS 2 in-flight deduplication — the `Map<packetId, Qos2State>` must check DUP flag against the pending-PUBREL map, not just accept/re-deliver blindly.

**Primary recommendation:** Port the `common/actor` framework files verbatim, write a simpler `MqttSessionHandler` than TBMQ's (~400 lines vs ~800), and implement each service (LWT, retained, session registry, packet ID allocator) as a focused `@Service` with a clean interface — the same interface/DefaultImpl naming convention from Phase 1.

---

## Standard Stack

### Core (already in pom.xml from Phase 1)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `io.netty:netty-all` | 4.1.122.Final (pinned) | MQTT decode/encode, TCP I/O | Ships `MqttDecoder`, `MqttEncoder`, `IdleStateHandler` |
| `org.springframework.boot:spring-boot-starter-*` | 3.5.3 | DI, lifecycle, actuator | Already wired |
| `org.projectlombok:lombok` | managed | `@Slf4j`, `@Data`, `@RequiredArgsConstructor` | Project-wide convention |

### Needs to be Added for Phase 2 Tests

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Eclipse Paho MQTTv3 | 1.2.5 | MQTT 3.1.1 client for integration tests | Test only — `@SpringBootTest` with a real broker |
| Eclipse Paho MQTTv5 | 1.2.5 | MQTTv5 client (useful for future) | Optional for Phase 2 tests |

**Version verification:**
- `io.netty:netty-all` 4.1.122.Final — confirmed from `lightweight/pom.xml` `netty.version` property
- Eclipse Paho v3: `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` — confirmed used in TBMQ application pom

**Installation (test scope addition to lightweight/pom.xml):**
```xml
<dependency>
    <groupId>org.eclipse.paho</groupId>
    <artifactId>org.eclipse.paho.client.mqttv3</artifactId>
    <version>1.2.5</version>
    <scope>test</scope>
</dependency>
```

---

## Architecture Patterns

### Recommended Package Structure (lightweight module)

```
lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/
├── server/
│   ├── MqttTcpServerBootstrap.java          # Phase 1 — extend pipeline
│   ├── MqttChannelInitializer.java          # Phase 1 — add codec + handler
│   ├── ConnectionCountHandler.java          # Phase 1
│   └── MqttSessionHandler.java              # NEW Phase 2 — ~400 lines
├── actors/
│   ├── TbActorSystem.java                   # PORT from common/actor
│   ├── TbActorMailbox.java                  # PORT from common/actor
│   ├── AbstractTbActor.java                 # PORT from common/actor
│   ├── TbActorRef.java                      # PORT from common/actor
│   ├── TbActorCtx.java                      # PORT from common/actor
│   ├── TbActorId.java                       # PORT from common/actor
│   ├── TbActor.java                         # PORT from common/actor
│   ├── TbActorCreator.java                  # PORT from common/actor
│   ├── TbActorSystemSettings.java           # PORT from common/actor
│   ├── DefaultTbActorSystem.java            # PORT from common/actor
│   ├── TbActorMailbox.java                  # PORT from common/actor
│   ├── Dispatcher.java                      # PORT from common/actor
│   ├── InitFailureStrategy.java             # PORT from common/actor
│   ├── ProcessFailureStrategy.java          # PORT from common/actor
│   ├── msg/
│   │   ├── TbActorMsg.java                  # PORT interface
│   │   └── MsgType.java                     # SIMPLIFIED (Phase 2 types only)
│   └── client/
│       └── ClientActor.java                 # NEW — simplified vs TBMQ's
├── session/
│   ├── ClientSessionCtx.java                # NEW — channel + session state
│   ├── ClientSessionRegistry.java           # NEW interface
│   ├── DefaultClientSessionRegistry.java    # NEW — ConcurrentHashMap<clientId, ClientSessionCtx>
│   ├── DisconnectReason.java                # NEW — simplified from TBMQ
│   └── DisconnectReasonType.java            # NEW — Phase 2 relevant types only
├── service/
│   ├── mqtt/
│   │   ├── MqttMessageGenerator.java        # NEW interface
│   │   ├── DefaultMqttMessageGenerator.java # NEW — Netty message builders
│   │   ├── PublishMsg.java                  # NEW — payload + metadata POJO
│   │   ├── retain/
│   │   │   ├── RetainedMsgService.java      # NEW interface
│   │   │   └── DefaultRetainedMsgService.java # NEW — ConcurrentHashMap<topic, RetainedMsg>
│   │   ├── will/
│   │   │   ├── LastWillService.java         # NEW interface
│   │   │   └── DefaultLastWillService.java  # NEW — ConcurrentHashMap<sessionId, WillMsg>
│   │   └── keepalive/
│   │       ├── KeepAliveService.java        # NEW interface
│   │       └── DefaultKeepAliveService.java # NEW — dynamic IdleStateHandler management
│   └── subscription/
│       ├── SubscriptionRegistry.java        # NEW interface
│       └── DefaultSubscriptionRegistry.java # NEW — ConcurrentHashMap<topic, Set<Subscription>>
├── packet/
│   └── PacketIdAllocator.java              # NEW — AtomicInteger(1..65535) per session
└── config/
    ├── NettyConfiguration.java              # Phase 1
    ├── StorageConfiguration.java            # Phase 1
    └── ActorSystemConfiguration.java       # NEW — ForkJoinPool dispatcher config
```

### Pattern 1: Extending the Netty Pipeline (Phase 1 to Phase 2)

**What:** Add `MqttDecoder`, `MqttEncoder`, and `MqttSessionHandler` to the existing Netty pipeline in `MqttChannelInitializer`.

**When to use:** Single touch point — `initChannel()` in `MqttChannelInitializer`.

**Example:**
```java
// Source: application/src/main/java/org/thingsboard/mqtt/broker/server/AbstractMqttChannelInitializer.java
@Override
protected void initChannel(SocketChannel ch) {
    ch.pipeline()
        .addLast("idle", new IdleStateHandler(0, 0, 0))        // Phase 1 (disable, reconfigure after CONNECT)
        .addLast("connectionCount", connectionCountHandler)     // Phase 1
        .addLast("decoder", new MqttDecoder(maxPayloadSize, maxClientIdLength)) // Phase 2 NEW
        .addLast("encoder", MqttEncoder.INSTANCE)               // Phase 2 NEW
        .addLast("handler", handlerFactory.create());           // Phase 2 NEW — new MqttSessionHandler per channel
}
```

**Note:** `MqttDecoder` constructor in Netty 4.1.x accepts `(int maxBytesInMessage, int maxClientIdLength)`. Default `maxClientIdLength` is 23 per spec SHOULD; make configurable. Default `maxBytesInMessage` is 8092; make configurable via `tbmq.mqtt.max-payload-size`.

### Pattern 2: Actor-Per-Client — Porting from common/actor

**What:** The `DefaultTbActorSystem` / `TbActorMailbox` / `AbstractTbActor` trio provides an actor model with `ConcurrentLinkedQueue` mailboxes, `AtomicBoolean busy` flag for single-threaded execution per actor, and configurable throughput (messages-per-dispatch-cycle). Port all files from `common/actor/src/main/java/org/thingsboard/mqtt/broker/actors/` into `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/`.

**Key simplifications for lightweight vs TBMQ:**
- Single dispatcher `"client-dispatcher"` backed by `ForkJoinPool.commonPool()` or a named `ForkJoinPool` — no `persisted-device-dispatcher`.
- Actor ID is `TbTypeActorId("client", clientId)` — same pattern.
- `TbActorSystemSettings`: reduce `schedulerPoolSize` to 1; `actorThroughput` default of 10 is fine.
- No `ActorStatsManager` complexity needed — Phase 2 actors can use simple logging.

**Lifecycle:** `DefaultTbActorSystem` is a `SmartLifecycle` bean at phase 1 (starts after RocksDB at MIN_VALUE, starts before Netty at phase 0... wait — actors must start BEFORE Netty). Correct ordering:
- RocksDB: `Integer.MIN_VALUE`
- Actor system: `-1` (before Netty, after RocksDB)
- Netty TCP: `0`

**Example — creating/getting an actor on CONNECT:**
```java
// In MqttSessionHandler.initSession() or delegated to ClientMqttActorManager
TbActorId actorId = new TbTypeActorId("client", clientId);
TbActorRef ref = actorSystem.getActor(actorId);
if (ref == null) {
    ref = actorSystem.createRootActor("client-dispatcher",
        new ClientActorCreator(actorSystemContext, clientId));
}
ref.tell(new SessionInitMsg(sessionCtx, ...));
```

### Pattern 3: MqttSessionHandler — Simplified vs TBMQ

**What:** Per-channel Netty `ChannelInboundHandlerAdapter` that: (1) decodes MqttMessage from Netty, (2) checks DecoderResult for malformed packets, (3) enforces first-message-must-be-CONNECT rule, (4) routes all message types to the client's actor via tell().

**Key differences from TBMQ's MqttSessionHandler:**
- No rate limiting (Phase 2 has no auth/rate-limit)
- No SSL handler reference
- No `ClientLogger` or stats reporting
- `ReferenceCountUtil.safeRelease(msg)` in `finally` block is MANDATORY — Netty ByteBuf leak prevention
- `ch.closeFuture().addListener(handler)` wires disconnect on channel close

**Example:**
```java
// Source: application/src/main/java/org/thingsboard/mqtt/broker/server/MqttSessionHandler.java (simplified)
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    try {
        if (!(msg instanceof MqttMessage message)) {
            disconnect(DisconnectReasonType.ON_PROTOCOL_ERROR, "Unknown message type");
            return;
        }
        DecoderResult result = message.decoderResult();
        if (!result.isSuccess()) {
            if (result.cause() instanceof TooLongFrameException) {
                disconnect(DisconnectReasonType.ON_PACKET_TOO_LARGE);
            } else {
                disconnect(DisconnectReasonType.ON_MALFORMED_PACKET, result.cause().getMessage());
            }
            return;
        }
        processMqttMsg(message);
    } finally {
        ReferenceCountUtil.safeRelease(msg);   // CRITICAL — always release ByteBuf
    }
}
```

### Pattern 4: Dynamic Keep-Alive with IdleStateHandler

**What:** MQTT spec requires the broker to disconnect a client that has not sent any packet within 1.5 × keep-alive seconds. Netty's `IdleStateHandler` fires `IdleStateEvent` on the channel when no read activity occurs.

**Implementation:** Phase 1 adds `IdleStateHandler(0, 0, 0)` (no timeout). After CONNECT is processed, replace this handler in-pipeline with the actual client keep-alive. Then handle `userEventTriggered(IdleStateEvent)` in `MqttSessionHandler`.

**The 1.5x multiplier is a MUST in spec section 3.1.2.10:**
```java
// In MqttSessionHandler — called after actor processes CONNECT and returns keepAliveSeconds
void configureKeepAlive(ChannelHandlerContext ctx, int keepAliveSeconds) {
    if (keepAliveSeconds == 0) return; // 0 means disabled per spec
    int timeoutSeconds = keepAliveSeconds + (keepAliveSeconds / 2); // 1.5x
    ctx.pipeline().replace("idle", "idle",
        new IdleStateHandler(timeoutSeconds, 0, 0)); // reader timeout only
}

@Override
public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt instanceof IdleStateEvent idleEvt &&
            idleEvt.state() == IdleState.READER_IDLE) {
        disconnect(DisconnectReasonType.ON_KEEP_ALIVE, "Keep-alive timeout");
    }
    super.userEventTriggered(ctx, evt);
}
```

**Critical detail:** `IdleStateHandler.replace()` must happen on the Netty I/O thread (it always is, since CONNECT is processed in `channelRead`). Safe to call `ctx.pipeline().replace()` from within `channelRead`.

### Pattern 5: Client Takeover (Single-Node Simplification)

**What:** When a new CONNECT arrives for an existing clientId, the existing session must be closed first, with LWT suppressed for that close, then the new session initializes.

**Single-node simplification vs TBMQ:** No Kafka-based `ClientSessionEventService` request/response. Just atomically replace the actor registry entry.

**Implementation approach:**
```
1. MqttSessionHandler receives CONNECT for clientId="foo"
2. Tell actor system: SESSION_INIT_MSG for "foo"
3. If actor for "foo" already exists AND has an active session:
   a. Tell old actor: TAKEOVER_DISCONNECT_MSG (reason=ON_CONFLICTING_SESSIONS)
   b. Actor closes old channel, skips LWT (DisconnectReasonType.ON_CONFLICTING_SESSIONS
      → allowsLastWillOnDisconnect() = ??? — see below)
   c. New SessionInitMsg proceeds on the SAME actor (actor persists, session state resets)
4. Send CONNACK to new connection

LWT suppression rule: DisconnectReasonType.ON_CONFLICTING_SESSIONS must return false
from allowsLastWillOnDisconnect(). Verify this is set correctly — in TBMQ's
DisconnectReasonType, ON_DISCONNECT_MSG is the only explicit exclusion.
For takeover, must explicitly exclude ON_CONFLICTING_SESSIONS from LWT delivery.
```

**PITFALL ALERT:** If LWT suppression is not wired correctly, client takeover fires spurious LWT to subscribers every time a client reconnects. See PITFALLS.md Pitfall 1.

### Pattern 6: QoS 2 State Machine

**What:** QoS 2 "exactly-once" delivery requires a 4-step handshake. The broker must track in-flight QoS 2 publishes in a per-session map to handle DUP retransmissions correctly.

```
Publisher → Broker: PUBLISH (QoS=2, packetId=X)
Broker stores packetId X in inboundQos2Map (state=AWAITING_PUBREL)
Broker → Publisher: PUBREC(X)

Publisher → Broker: PUBREL(X)
Broker delivers message to subscribers (ONE time)
Broker removes X from inboundQos2Map
Broker → Publisher: PUBCOMP(X)

// DUP handling — if publisher retransmits PUBLISH(QoS=2, DUP=true, packetId=X):
// X is already in inboundQos2Map → send PUBREC(X) again, do NOT re-deliver
```

**Data structure per session:**
```java
// In ClientSessionCtx or actor state
private final ConcurrentHashMap<Integer, Qos2InboundState> inboundQos2 = new ConcurrentHashMap<>();
// enum Qos2InboundState { AWAITING_PUBREL }
```

**Outbound QoS 2 (broker pushing to subscribers):**
```java
// When broker delivers QoS 2 to subscriber
private final ConcurrentHashMap<Integer, QueuedMsg> outboundQos2 = new ConcurrentHashMap<>();
// On PUBREC received: move state from AWAITING_PUBREC → AWAITING_PUBCOMP, send PUBREL
// On PUBCOMP received: remove from map
```

### Pattern 7: Packet ID Allocator

**What:** Outbound QoS 1 and QoS 2 messages require a unique packet ID (1-65535) that wraps around. IDs must be reused only after acknowledgment.

**Simple single-session implementation:**
```java
public class PacketIdAllocator {
    private final AtomicInteger counter = new AtomicInteger(0);
    private final Set<Integer> inUse = ConcurrentHashMap.newKeySet();

    public int nextPacketId() {
        for (int i = 0; i < 65535; i++) {
            int id = (counter.incrementAndGet() & 0xFFFF);
            if (id == 0) id = 1; // skip 0, not valid per spec
            if (inUse.add(id)) return id;
        }
        throw new IllegalStateException("No available packet IDs"); // 65535 in-flight
    }

    public void releasePacketId(int id) {
        inUse.remove(id);
    }
}
```

**Note:** PacketId allocator lives per session (`ClientSessionCtx`), not shared across clients.

### Pattern 8: Retained Message Service

**What:** `ConcurrentHashMap<String, RetainedMsg>` keyed by exact topic string. On SUBSCRIBE, for each subscription in the SUBSCRIBE packet, look up the map for an exact topic match and deliver if found.

**Spec requirements:**
- A PUBLISH with retain=1 and empty payload CLEARS the retained message for that topic
- A PUBLISH with retain=1 and non-empty payload SETS the retained message
- Retained messages are delivered with retain=1 flag set in the outgoing PUBLISH
- The QoS of the retained message delivery is `min(originalQoS, subscriptionQoS)`

**D-09 constraint:** Phase 2 exact-match only (D-06/D-07 apply). Wildcard retained message delivery deferred to Phase 3.

### Pattern 9: LWT Service

**What:** Store LWT on CONNECT (if will flag=true), execute on ungraceful disconnect. Suppress on clean DISCONNECT or client takeover.

**Storage:** `ConcurrentHashMap<UUID sessionId, WillMessage>`. Session ID (UUID) is used as key rather than clientId to handle takeover: when the old session is displaced, its sessionId is used to suppress its LWT.

**Delivery trigger:** When actor processes disconnect, check `disconnectReason.allowsLastWillOnDisconnect()`. If true, retrieve LWT by sessionId, look up matching subscriptions, deliver.

**Implementation from TBMQ (DefaultLastWillService pattern, simplified):**
- Store: `lastWillMessages.put(sessionId, willMsg)` at CONNECT time
- Remove without delivery: `ON_DISCONNECT_MSG`, `ON_CONFLICTING_SESSIONS`
- Remove with delivery: all other reasons (keep-alive, error, channel closed)
- No scheduler needed in Phase 2 — MQTT 3.1.1 has no will delay interval (that's MQTT 5.0)

### Anti-Patterns to Avoid

- **Not releasing ByteBuf:** Every `channelRead` MUST call `ReferenceCountUtil.safeRelease(msg)` in a `finally` block. Netty ref-counts buffers; leaks show up as out-of-memory or growing direct memory.
- **Processing non-CONNECT first packets:** The first packet on any connection MUST be CONNECT. Any other packet type received before a session is initialized must trigger a disconnect (no CONNACK). TBMQ throws `ProtocolViolationException` for this.
- **Sharing `IdleStateHandler` across channels:** `IdleStateHandler` is NOT `@Sharable`. Each channel gets a new instance. `MqttEncoder.INSTANCE` IS shareable. `MqttDecoder` is NOT shareable — new instance per channel.
- **Firing LWT on client takeover:** Must track disconnect reason type; `ON_CONFLICTING_SESSIONS` must suppress LWT.
- **DUP PUBLISH re-delivery (QoS 2):** When broker receives PUBLISH with DUP=true and the packetId is already in the inbound QoS 2 map (awaiting PUBREL), broker must send PUBREC again and NOT deliver message a second time to subscribers.
- **Blocking Netty I/O threads in actor dispatch:** `tell()` enqueues a message to the actor's mailbox; it must never block. If the actor is busy, the mailbox queues the message and returns immediately. Never call `get()` on a future from a Netty I/O thread.
- **Using `channel.write()` without `flush()`:** Always use `writeAndFlush()` for MQTT response messages, or write multiple + single `flush()`. Netty buffers writes until flush — clients waiting for CONNACK/SUBACK will time out.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| MQTT packet parsing | Custom byte-level parser | `io.netty:netty-codec-mqtt` `MqttDecoder` / `MqttEncoder` | Handles all MQTT 3.1.1 + 5.0 packet types, variable-length encoding, UTF-8 strings, QoS fields. Huge correctness surface. |
| MQTT packet construction | Manual `ByteBuf` writing | Netty `MqttMessageBuilders` + `MqttFixedHeader` builders | Correct field encoding; handles variable header, payload. |
| Per-client message serialization | `synchronized` blocks or lock-per-client maps | Actor mailbox (`TbActorMailbox` from `common/actor`) | Battle-tested; `ConcurrentLinkedQueue` + `AtomicBoolean busy` avoids lock contention and priority inversion. |
| Topic pattern matching | Naive string split loop | Phase 2: exact `ConcurrentHashMap` lookup (D-06). Phase 3: port `ConcurrentMapSubscriptionTrie` | Wildcard matching has edge cases (`#` must match zero segments, `+` must not match `/`) that are trivially wrong to implement. |
| Keep-alive timeout tracking | `ScheduledExecutorService` per client | Netty `IdleStateHandler` | Purpose-built; fires `IdleStateEvent` on reader inactivity; zero overhead when traffic is flowing. |

**Key insight:** The MQTT codec layer is where ~80% of spec compliance bugs live. Netty's `MqttDecoder` handles malformed packet detection, max payload size enforcement (`TooLongFrameException`), and protocol version parsing. Never bypass it.

---

## Common Pitfalls

### Pitfall 1: LWT Fires on Client Takeover

**What goes wrong:** When client A connects with a will, then client B reconnects with the same clientId, the broker sends client A's LWT to subscribers. This is incorrect — takeover is not an unexpected disconnect.

**Why it happens:** Disconnect processing uses a single code path without distinguishing takeover from network failure.

**How to avoid:** `DisconnectReasonType.ON_CONFLICTING_SESSIONS` must return `false` from `allowsLastWillOnDisconnect()`. The TBMQ `DisconnectReasonType` only excludes `ON_DISCONNECT_MSG` by default — explicitly add `ON_CONFLICTING_SESSIONS` to the exclusion set.

**Warning signs:** Integration test for reconnect shows LWT delivered to subscriber.

---

### Pitfall 2: QoS 2 Double Delivery on DUP Retransmit

**What goes wrong:** Client retransmits PUBLISH (DUP=true, QoS=2, packetId=X) after not receiving PUBREC. Broker treats it as a new message and delivers it a second time.

**Why it happens:** DUP flag check only happens if the inbound QoS 2 state map is checked first. If state map is not checked, every DUP retransmit triggers re-delivery.

**How to avoid:** On receiving QoS 2 PUBLISH: if packetId is in `inboundQos2Map`, send PUBREC and return. Do not deliver. Only deliver when packetId is NOT in the map.

**Warning signs:** MQTT subscriber receives duplicate messages in QoS 2 flows under network stress.

---

### Pitfall 3: ByteBuf Leak from Missing ReferenceCountUtil.safeRelease

**What goes wrong:** Netty ref-counts `ByteBuf` objects. If `channelRead` exits without releasing, the direct memory grows unboundedly. Under load, the broker OOMs on direct memory, not heap.

**Why it happens:** Exception thrown before `safeRelease()` in `channelRead`. TBMQ uses `try { ... } finally { ReferenceCountUtil.safeRelease(msg); }` universally.

**How to avoid:** Always wrap `channelRead` body in try-finally with `safeRelease`. Enable Netty's leak detector in tests: `-Dio.netty.leakDetection.level=PARANOID` in test JVM args.

**Warning signs:** `LEAK: ByteBuf.release() was not called before it's garbage-collected` in logs.

---

### Pitfall 4: IdleStateHandler Not Per-Channel

**What goes wrong:** `IdleStateHandler` is instantiated once as a Spring bean and reused across channels. This causes all channels to share the same idle timeout tracking, resulting in spurious or missing keep-alive disconnects.

**Why it happens:** Developer follows `@ChannelHandler.Sharable` pattern from `ConnectionCountHandler` without checking whether `IdleStateHandler` is sharable.

**How to avoid:** `IdleStateHandler` is NOT `@Sharable`. Create a new instance in `initChannel()`. Similarly, `MqttDecoder` is NOT `@Sharable` — new instance per channel. Only `MqttEncoder.INSTANCE` and `ConnectionCountHandler` (which is explicitly `@Sharable`) are safe to share.

---

### Pitfall 5: MqttDecoder Default maxClientIdLength Is 23

**What goes wrong:** Netty's `MqttDecoder(int maxBytesInMessage)` 1-arg constructor uses default `maxClientIdLength=23` (MQTT spec SHOULD). Some clients use longer client IDs. Broker rejects valid connections.

**Why it happens:** The single-argument `MqttDecoder(maxPayloadSize)` constructor exists but doesn't expose `maxClientIdLength`. Must use the 2-argument form.

**How to avoid:** Always use `new MqttDecoder(maxPayloadSize, maxClientIdLength)` with a configurable `maxClientIdLength` property. Default to 23 for spec compliance, expose override via `tbmq.mqtt.max-client-id-length` config.

---

### Pitfall 6: Actor Dispatch Phase Ordering

**What goes wrong:** Actor system starts after Netty, so the first CONNECT packet arrives before actors can receive messages. `tell()` throws `TbActorNotRegisteredException`.

**Why it happens:** Both actor system and Netty use `SmartLifecycle`. Default phase is 0. If both are phase 0, start order is non-deterministic.

**How to avoid:** Actor system `SmartLifecycle.getPhase()` must return `-1` (or any value less than 0, greater than `Integer.MIN_VALUE` for RocksDB). Netty bootstrap returns `0`. Correct order: RocksDB starts at `Integer.MIN_VALUE`, actor system at `-1`, Netty at `0`. Stop order is reversed: Netty stops first (phase 0), then actor system (phase -1), then RocksDB (Integer.MIN_VALUE).

---

### Pitfall 7: Blocking in Netty I/O Thread During Actor Processing

**What goes wrong:** `MqttSessionHandler.channelRead()` calls actor `tell()` — this is fine. But if the session handler waits for a result from the actor (e.g., via a `CompletableFuture.get()`), it blocks the Netty I/O thread. All I/O on that event loop (other connections, heartbeats) stalls.

**Why it happens:** Developer wants synchronous processing for simplicity, uses `CompletableFuture.get()` on CONNECT processing.

**How to avoid:** Fire-and-forget actor tells only. For CONNACK, the actor sends CONNACK by holding a reference to the `ChannelHandlerContext` (stored in `ClientSessionCtx`). Never block the channel thread waiting for actor results.

---

## Code Examples

### Netty Pipeline Setup (Phase 2)
```java
// Source: AbstractMqttChannelInitializer.java + MqttChannelInitializer.java (Phase 1 base)
@Override
protected void initChannel(SocketChannel ch) {
    ch.pipeline()
        .addLast("idle", new IdleStateHandler(0, 0, 0))         // replaced after CONNECT
        .addLast("connectionCount", connectionCountHandler)      // Phase 1 @Sharable
        .addLast("decoder", new MqttDecoder(maxPayloadSize, maxClientIdLength)) // per-channel
        .addLast("encoder", MqttEncoder.INSTANCE)                // @Sharable
        .addLast("handler", sessionHandlerFactory.create());     // per-channel
    ch.closeFuture().addListener(handler); // disconnect on channel close
}
```

### CONNACK Response Builder
```java
// Source: io.netty.handler.codec.mqtt.MqttMessageBuilders (Netty 4.1.x)
MqttConnAckMessage connAck = MqttMessageBuilders.connAck()
        .returnCode(MqttConnectReturnCode.CONNECTION_ACCEPTED)
        .sessionPresent(false) // Clean Session=1 → always false in R1
        .build();
ctx.writeAndFlush(connAck);
```

### SUBACK with Return Codes
```java
// One return code per subscription in SUBSCRIBE packet
List<Integer> grantedQos = topicSubscriptions.stream()
        .map(sub -> sub.getQosLevel()) // grant requested QoS
        .collect(Collectors.toList());
MqttSubAckMessage subAck = MqttMessageBuilders.subAck()
        .packetId(subscribeMsg.variableHeader().messageId())
        .addGrantedQoses(grantedQos.stream().mapToInt(i -> i).toArray())
        .build();
ctx.writeAndFlush(subAck);
```

### QoS 1 Publish to Subscriber
```java
// Source: Pattern from DefaultMqttMessageGenerator equivalent
MqttPublishMessage outbound = MqttMessageBuilders.publish()
        .topicName(topic)
        .packetId(allocator.nextPacketId())
        .qos(MqttQoS.AT_LEAST_ONCE)
        .retained(false)
        .payload(Unpooled.wrappedBuffer(payload))
        .build();
subscriberChannel.writeAndFlush(outbound);
```

### Actor Tell Pattern from MqttSessionHandler
```java
// Source: MqttSessionHandler.java pattern
private void processMqttMsg(MqttMessage msg) {
    if (StringUtils.isEmpty(clientId)) {
        if (msg.fixedHeader().messageType() != MqttMessageType.CONNECT) {
            throw new ProtocolViolationException("First packet must be CONNECT");
        }
        initSession((MqttConnectMessage) msg);
    }
    switch (msg.fixedHeader().messageType()) {
        case PUBLISH -> actorManager.processMqttMsg(clientId,
                createMqttPublishMsg(sessionId, (MqttPublishMessage) msg));
        case SUBSCRIBE -> actorManager.processMqttMsg(clientId,
                createMqttSubscribeMsg(sessionId, (MqttSubscribeMessage) msg));
        // ... etc
    }
}
```

### Actor System Lifecycle Bean
```java
@Service
@Slf4j
@RequiredArgsConstructor
public class ClientActorSystemManager implements SmartLifecycle {

    private TbActorSystem actorSystem;

    @Override
    public int getPhase() { return -1; } // after RocksDB (MIN_VALUE), before Netty (0)

    @Override
    public void start() {
        TbActorSystemSettings settings = new TbActorSystemSettings(10, 1, 100000);
        actorSystem = new DefaultTbActorSystem(settings);
        actorSystem.createDispatcher("client-dispatcher",
                Executors.newWorkStealingPool()); // ForkJoinPool equivalent
        running = true;
    }

    @Override
    public void stop() {
        actorSystem.destroy();
        running = false;
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Custom MQTT byte parsers | Netty `MqttDecoder`/`MqttEncoder` | Netty 4.0+ | Zero custom parsing needed |
| Thread-per-connection | Event loop + actor model | Industry shift ~2015 | Supports 10K+ connections on single node |
| Kafka for in-process routing | `channel.writeAndFlush()` direct delivery | This phase (D-04) | Lower latency, no serialization overhead |
| `IdleStateHandler(keepAlive)` at pipeline setup | Dynamic `pipeline().replace()` after CONNECT | Existing TBMQ pattern | Correct per-client keep-alive values |

---

## Open Questions

1. **`$SYS` topic behavior (D-08)**
   - What we know: D-08 specifies `$SYS` topic subscription behavior must be handled
   - What's unclear: Should subscriptions to `$SYS/#` be silently accepted but never matched (no `$SYS` messages published in Phase 2), or rejected with SUBACK failure code?
   - Recommendation: Accept with granted QoS (same as any other subscription), deliver nothing. `$SYS` topics can be populated in Phase 7 (OPS-01 metrics). Document in code comment.

2. **Packet ID wrap-around under concurrent outbound QoS 1/2**
   - What we know: Single-threaded actor guarantees sequential ID allocation within a session — no concurrency risk inside actor
   - What's unclear: Actor processes incoming messages one-at-a-time, so outbound delivery to subscribers happens on actor's thread. If one actor delivers to 1000 subscribers sequentially, last subscriber could see ID wrap if IDs 1-999 are also in flight. Not a real risk in Phase 2 (inline delivery completes before next message is processed).
   - Recommendation: Simple `AtomicInteger` allocator is sufficient for Phase 2; no further action needed.

3. **Actor stop delay for named clients (TBMQ has 5-second delay)**
   - What we know: TBMQ delays actor stop to handle reconnects within a short window for the same clientId
   - What's unclear: Is this needed in Phase 2? In TBMQ, it avoids recreating actor on rapid reconnect. For Phase 2, actor can be destroyed immediately on disconnect and recreated on next CONNECT.
   - Recommendation: Destroy actor immediately on disconnect (simpler). Add delayed stop in a later phase if rapid reconnect tests show performance issues.

---

## Environment Availability

Step 2.6: This phase is purely code changes within the lightweight Maven module. No new external dependencies (no new databases, no new CLI tools). Maven, JDK 17, and the existing project dependencies are sufficient. Eclipse Paho MQTT client for tests is a Maven dependency, not a system tool.

**SKIPPED — no external tool availability check required beyond Phase 1 foundation.**

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (via `spring-boot-starter-test`) + AssertJ |
| Config file | `lightweight/pom.xml` — Surefire 3.2.5 already configured |
| Quick run command | `mvn -f lightweight/pom.xml test -pl . -Dtest="*Test" -q` |
| Full suite command | `mvn -f lightweight/pom.xml test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PROTO-01 | Paho client connects and receives CONNACK(0) | Integration | `mvn test -Dtest=MqttConnectIntegrationTest -pl lightweight` | ❌ Wave 0 |
| PROTO-02 | QoS 0/1/2 publish/subscribe roundtrip | Integration | `mvn test -Dtest=MqttQosIntegrationTest -pl lightweight` | ❌ Wave 0 |
| PROTO-03 | SUBSCRIBE grants QoS in SUBACK; UNSUBSCRIBE removes subscription | Integration | `mvn test -Dtest=MqttSubscribeIntegrationTest -pl lightweight` | ❌ Wave 0 |
| PROTO-04 | Client disconnected after keep-alive expiry; PINGREQ/PINGRESP | Integration | `mvn test -Dtest=MqttKeepAliveIntegrationTest -pl lightweight` | ❌ Wave 0 |
| PROTO-05 | Retained message delivered to new subscriber | Integration | `mvn test -Dtest=MqttRetainedMsgIntegrationTest -pl lightweight` | ❌ Wave 0 |
| PROTO-06 | LWT delivered on ungraceful disconnect | Integration | `mvn test -Dtest=MqttLwtIntegrationTest -pl lightweight` | ❌ Wave 0 |
| PROTO-07 | Session cleared on disconnect; Clean Session=0 behaves as CS=1 | Integration | `mvn test -Dtest=MqttSessionIntegrationTest -pl lightweight` | ❌ Wave 0 |
| PROTO-11 | Client takeover: old session disconnected, LWT suppressed, new session active | Integration | `mvn test -Dtest=MqttClientTakeoverTest -pl lightweight` | ❌ Wave 0 |
| TRAN-01 | TCP server listens on port 1883, accepts MQTT connections | Integration | `mvn test -Dtest=NettyServerBootstrapTest` | ✅ Exists |

### Sampling Rate

- **Per task commit:** `mvn -f lightweight/pom.xml test -Dtest="*Test" -q`
- **Per wave merge:** `mvn -f lightweight/pom.xml test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttConnectIntegrationTest.java` — covers PROTO-01
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttQosIntegrationTest.java` — covers PROTO-02
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttSubscribeIntegrationTest.java` — covers PROTO-03
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttKeepAliveIntegrationTest.java` — covers PROTO-04
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttRetainedMsgIntegrationTest.java` — covers PROTO-05
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttLwtIntegrationTest.java` — covers PROTO-06
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttSessionIntegrationTest.java` — covers PROTO-07
- [ ] `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttClientTakeoverTest.java` — covers PROTO-11
- [ ] Add Eclipse Paho MQTTv3 dependency (test scope) to `lightweight/pom.xml`
- [ ] Create `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/AbstractMqttIntegrationTest.java` — shared `@SpringBootTest` base class with `port=0`, Paho client factory, auto-connect/disconnect lifecycle

---

## Sources

### Primary (HIGH confidence)

- TBMQ `common/actor` source — `DefaultTbActorSystem.java`, `TbActorMailbox.java`, `AbstractTbActor.java` — actor framework internals verified by direct source read
- TBMQ `MqttSessionHandler.java` — message routing and error handling patterns verified by direct source read
- TBMQ `AbstractMqttChannelInitializer.java` — Netty pipeline setup with `MqttDecoder`/`MqttEncoder` verified by direct source read
- TBMQ `DefaultLastWillService.java` — LWT storage and delivery patterns verified
- TBMQ `DisconnectReasonType.java` — `allowsLastWillOnDisconnect()` behavior verified
- `lightweight/pom.xml` — dependency versions confirmed (Netty 4.1.122.Final, Spring Boot 3.5.3)
- `lightweight/src/main/java/.../server/MqttChannelInitializer.java` — Phase 1 pipeline placeholders confirmed
- `.planning/research/PITFALLS.md` — ByteBuf leak, LWT takeover, QoS 2 DUP pitfalls
- MQTT 3.1.1 spec section 3.1.2.10 (keep-alive): 1.5x multiplier is a MUST

### Secondary (MEDIUM confidence)

- TBMQ `ConnectServiceImpl.java` — client takeover and keep-alive registration patterns (TBMQ uses Kafka-based coordination; lightweight simplifies to direct actor lookup)
- TBMQ `NettyMqttConverter.java` — packet-to-actor-message conversion patterns
- `MsgType.java` — actor message type enum (will be simplified for Phase 2 scope)

### Tertiary (LOW confidence)

- None — all critical claims sourced from project codebase directly.

---

## Project Constraints (from CLAUDE.md)

| Constraint | How It Affects Phase 2 |
|------------|----------------------|
| Java 17+ | All new code targets Java 17; use records, sealed classes, pattern matching where appropriate |
| Spring Boot 3.4+ / 3.5.3 | `@RequiredArgsConstructor` DI, `SmartLifecycle`, `@Service`/`@Component` |
| Netty 4.1.x pinned (not 4.2) | `MqttDecoder`, `MqttEncoder`, `IdleStateHandler` — all 4.1.x APIs. Do not use 4.2 APIs. |
| Lombok `@Slf4j`, `@Data`, `@Builder` | Apply to all new service and config classes |
| Interface + Default naming | `SubscriptionRegistry` + `DefaultSubscriptionRegistry`, `LastWillService` + `DefaultLastWillService`, etc. |
| `@RequiredArgsConstructor` for DI | Use `private final` fields; no `@Autowired` on fields in new code |
| `log.{}` parameterized messages | No string concatenation in log calls |
| Root package `org.thingsboard.mqtt.broker.lightweight` | All new Phase 2 classes under this package |
| GSD workflow enforcement | All file edits through `/gsd:execute-phase`; no direct edits outside GSD workflow |

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in pom.xml; Paho version confirmed from TBMQ application pom
- Architecture: HIGH — patterns sourced directly from TBMQ codebase; actor framework files read in full
- Pitfalls: HIGH — TBMQ source confirms LWT suppress logic, ByteBuf release pattern, IdleStateHandler non-shareability
- Test strategy: MEDIUM — integration test structure inferred from Phase 1 patterns; actual test class creation is Wave 0 work

**Research date:** 2026-04-06
**Valid until:** 2026-06-06 (stable libraries; MQTT spec is frozen)
