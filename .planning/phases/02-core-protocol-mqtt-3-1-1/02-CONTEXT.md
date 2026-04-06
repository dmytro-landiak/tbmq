# Phase 2: Core Protocol (MQTT 3.1.1) - Context

**Gathered:** 2026-04-06
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase delivers a fully compliant MQTT 3.1.1 broker over TCP: CONNECT/CONNACK, PUBLISH with QoS 0/1/2 (PUBACK/PUBREC/PUBREL/PUBCOMP), SUBSCRIBE/SUBACK, UNSUBSCRIBE/UNSUBACK, PINGREQ/PINGRESP, DISCONNECT, retained messages (in-memory), Last Will and Testament, keep-alive enforcement, client takeover, and Clean Session behavior. No authentication in this phase — all clients are accepted. No wildcard topic delivery — wildcard subscriptions are accepted and stored but only exact topic matching delivers messages (Phase 3 enables wildcard delivery via the subscription trie).

</domain>

<decisions>
## Implementation Decisions

### Session Architecture
- **D-01:** Use actor-per-client model — each client ID gets a dedicated actor with a mailbox queue, guaranteeing sequential per-client message processing. This matches TBMQ's proven pattern and provides natural isolation for client takeover, QoS state tracking, and LWT management.
- **D-02:** Port TBMQ's `common/actor` framework (TbActorSystem, TbActorMailbox, AbstractTbActor, TbActorRef, TbActorCreator) into the lightweight project. Battle-tested implementation preferred over a custom lightweight actor system.
- **D-03:** Single dispatcher (ForkJoinPool) is sufficient for single-node — no need for TBMQ's multi-dispatcher complexity (client-dispatcher vs persisted-device-dispatcher).

### Message Routing
- **D-04:** Phase 2 uses direct inline delivery — publisher's actor looks up matching subscriptions from the registry and writes directly to each subscriber's channel (`channel.writeAndFlush()`). No dispatch queue or intermediary service.
- **D-05:** Phase 3 refactors inline delivery into an abstracted dispatch service with LinkedBlockingQueue behind PublishMsgQueueFactory interface (per STATE.md decision), adding backpressure and non-blocking Netty handoff.

### Subscription Matching
- **D-06:** Subscription registry uses `ConcurrentHashMap<String, Set<Subscription>>` for exact topic matching. Simple, minimal, easy to test. Phase 3 replaces with the concurrent wildcard subscription trie.
- **D-07:** Wildcard subscriptions (`+`/`#`) are accepted and stored (valid SUBACK return codes), but only exact topic matches trigger message delivery in Phase 2. Phase 3 enables wildcard delivery.

### MQTT Spec Compliance
- **D-08:** Spec-complete MQTT 3.1.1 implementation — all MUST and SHOULD requirements covered in Phase 2. No compliance debt deferred to later phases. Includes:
  - Malformed packet detection and disconnect
  - Correct CONNACK return codes for all error conditions
  - Packet ID tracking (1-65535) with proper reuse after acknowledgment
  - DUP flag handling on QoS 1/2 retransmissions
  - Max client ID length validation (23 chars per spec SHOULD; configurable)
  - Empty client ID with Clean Session=1 behavior (broker assigns ID)
  - Protocol violation disconnect per spec MUST requirements
  - Configurable maximum payload size enforcement
  - `$SYS` topic subscription behavior

### Retained Messages
- **D-09:** Retained messages stored in-memory only (`ConcurrentHashMap<String, RetainedMessage>`). Per PROJECT.md constraints, retained messages do not survive broker restarts in R1. Delivered to new subscribers on matching topics immediately upon SUBSCRIBE.

### Clean Session
- **D-10:** Clean Session=1 is the only supported mode in R1. All sessions are in-memory and not persisted across restarts. Clean Session=0 connects are accepted but behave as Clean Session=1 (no persistent session restoration). Persistent sessions are deferred to R2 (PERS-01, PERS-02).

### Claude's Discretion
- Exact class names and package layout for protocol handlers (follow TBMQ naming conventions per D-03 from Phase 1)
- QoS 2 state machine implementation details (in-flight message tracking data structures)
- Packet ID allocator implementation (as long as it covers 1-65535 range with proper reuse)
- LWT storage and delivery mechanism internals
- Keep-alive timer implementation approach (IdleStateHandler configuration)
- Client takeover coordination internals (as long as existing session is closed, LWT suppressed for takeover, new session takes over cleanly)
- MQTT message builder/generator utility structure

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Existing TBMQ Codebase (protocol patterns to reference)
- `application/src/main/java/org/thingsboard/mqtt/broker/server/MqttSessionHandler.java` -- MQTT session handler pattern (actor delegation, message routing, error handling)
- `application/src/main/java/org/thingsboard/mqtt/broker/server/AbstractMqttChannelInitializer.java` -- Netty pipeline setup with MqttDecoder/MqttEncoder
- `common/actor/src/main/java/org/thingsboard/mqtt/broker/actors/TbActorSystem.java` -- Actor system to port
- `common/actor/src/main/java/org/thingsboard/mqtt/broker/actors/TbActorMailbox.java` -- Actor mailbox implementation to port
- `common/actor/src/main/java/org/thingsboard/mqtt/broker/actors/AbstractTbActor.java` -- Base actor class to port
- `application/src/main/java/org/thingsboard/mqtt/broker/actors/client/ClientActor.java` -- Per-client actor pattern to reference
- `application/src/main/java/org/thingsboard/mqtt/broker/service/mqtt/retain/` -- Retained message handling patterns
- `application/src/main/java/org/thingsboard/mqtt/broker/service/mqtt/will/` -- LWT handling patterns
- `application/src/main/java/org/thingsboard/mqtt/broker/service/subscription/` -- Subscription management patterns

### Lightweight Project (Phase 1 foundation)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttChannelInitializer.java` -- Pipeline to extend (add MqttDecoder, MqttEncoder, MqttSessionHandler)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttTcpServerBootstrap.java` -- TCP server bootstrap (already running)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/ConnectionCountHandler.java` -- Connection tracking (shared handler)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/storage/rocksdb/DefaultRocksDbStorage.java` -- RocksDB storage (not used for protocol state in Phase 2)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/NettyConfiguration.java` -- Netty config properties

### Research Documents
- `.planning/research/ARCHITECTURE.md` -- Component boundaries and build order
- `.planning/research/PITFALLS.md` -- Netty ByteBuf leaks, shutdown ordering pitfalls

### Project Context
- `.planning/PROJECT.md` -- Core value, constraints, key decisions
- `.planning/REQUIREMENTS.md` -- PROTO-01 through PROTO-07, PROTO-11, TRAN-01
- `.planning/phases/01-foundation/01-CONTEXT.md` -- Phase 1 decisions (naming, RocksDB, Netty, Docker)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `common/actor/` -- Full actor framework (TbActorSystem, TbActorMailbox, AbstractTbActor) to port into lightweight project
- `MqttChannelInitializer` -- Phase 1 pipeline with placeholder slots for MqttDecoder, MqttEncoder, MqttSessionHandler
- `ConnectionCountHandler` -- Shared connection counter already in pipeline
- `NettyConfiguration` -- Server config properties (port, threads, max connections)
- `BrokerMetricsService` -- Micrometer metrics already wired

### Established Patterns
- Netty pipeline: IdleStateHandler -> ConnectionCountHandler -> (Phase 2 adds: MqttDecoder -> MqttEncoder -> MqttSessionHandler)
- SmartLifecycle ordering: RocksDB at Integer.MIN_VALUE, Netty at phase 0
- Spring `@Component`/`@Service` with `@RequiredArgsConstructor` for DI
- Lombok `@Slf4j`, `@Data`, `@Builder` usage
- Interface + Default implementation naming (D-03 from Phase 1)

### Integration Points
- `MqttChannelInitializer.initChannel()` -- extend pipeline with MQTT codec and session handler
- `IdleStateHandler` -- reconfigure with actual keep-alive timeouts per client's CONNECT packet
- Actor system starts as a SmartLifecycle bean (phase between RocksDB and Netty, or same as Netty)
- Caffeine cache available for session state hot-path lookups

</code_context>

<specifics>
## Specific Ideas

- Netty's built-in `MqttDecoder` and `MqttEncoder` (from `io.netty:netty-codec-mqtt`) handle all MQTT 3.1.1 packet parsing — no need to write custom codec
- TBMQ's `MqttSessionHandler` is ~800 lines and deeply integrated with the actor system, rate limiting, and stats reporting — reference the structure but write a cleaner version for lightweight
- Keep-alive enforcement: reconfigure `IdleStateHandler` dynamically after CONNECT based on the client's keep-alive value (spec requires broker to wait 1.5x keep-alive before disconnecting)
- Client takeover: single-node makes this simpler than TBMQ (no Kafka-based session event coordination) — just look up existing actor by clientId, send disconnect, replace
- Pin Netty 4.1.x per STATE.md decision — do not let dependency management upgrade to 4.2

</specifics>

<deferred>
## Deferred Ideas

None -- discussion stayed within phase scope

</deferred>

---

*Phase: 02-core-protocol-mqtt-3-1-1*
*Context gathered: 2026-04-06*
