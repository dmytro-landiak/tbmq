# Architecture Research

**Domain:** Lightweight embedded MQTT broker (single-node, zero external dependencies)
**Researched:** 2026-04-02
**Confidence:** HIGH — based on the existing TBMQ codebase analysis combined with MQTT broker architecture patterns from official documentation and verified sources.

---

## Standard Architecture

### System Overview

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         TRANSPORT LAYER (Netty)                           │
│                                                                            │
│  ┌────────────┐  ┌────────────┐  ┌─────────────┐  ┌─────────────────┐   │
│  │  TCP:1883  │  │  TLS:8883  │  │   WS:8084   │  │   WSS:8085      │   │
│  │ (NioELG)  │  │ (NioELG)  │  │  (NioELG)   │  │   (NioELG)      │   │
│  └─────┬──────┘  └─────┬──────┘  └──────┬──────┘  └───────┬─────────┘   │
│        │               │                │                  │              │
│        └───────────────┴────────────────┴──────────────────┘              │
│                                    │                                       │
│                    MqttSessionHandler (per-channel)                        │
│           [decode → rate-limit → auth → actor-dispatch]                    │
└────────────────────────────────────┬───────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼───────────────────────────────────────┐
│                         ACTOR LAYER (per-client)                            │
│                                                                              │
│  ┌───────────────────┐  ┌───────────────────┐  ┌───────────────────────┐   │
│  │   ClientActor     │  │   ClientActor     │  │   ClientActor  ...    │   │
│  │  [state machine]  │  │  [state machine]  │  │   [state machine]     │   │
│  │  SessionState:    │  │  SessionState:    │  │   one per clientId    │   │
│  │  INIT/CONNECTED   │  │  CONNECTED        │  │                       │   │
│  └────────┬──────────┘  └────────┬──────────┘  └───────────┬───────────┘   │
│           │                      │                          │                │
│           └──────────────────────┴──────────────────────────┘                │
│                                    │                                          │
│                          Actor dispatcher                                     │
│                       (client-dispatcher pool)                                │
└────────────────────────────────────┬─────────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼─────────────────────────────────────────┐
│                         SERVICE LAYER                                         │
│                                                                                │
│  ┌─────────────────┐  ┌──────────────────┐  ┌────────────────────────────┐   │
│  │  AuthService    │  │ SubscriptionSvc  │  │   MsgDispatcherService     │   │
│  │  (RocksDB read) │  │  (in-mem trie)   │  │   (in-process queue)       │   │
│  └─────────────────┘  └──────────────────┘  └────────────────────────────┘   │
│                                                                                │
│  ┌─────────────────┐  ┌──────────────────┐  ┌────────────────────────────┐   │
│  │  RetainedMsg    │  │  SessionService  │  │   LwtService               │   │
│  │  (in-mem trie)  │  │  (in-mem map)    │  │   (in-mem store)           │   │
│  └─────────────────┘  └──────────────────┘  └────────────────────────────┘   │
│                                                                                │
│  ┌─────────────────┐  ┌──────────────────┐  ┌────────────────────────────┐   │
│  │  QoSTracker     │  │  InProcessCache  │  │   MetricsService           │   │
│  │  (in-mem map)   │  │  (Caffeine)      │  │   (Micrometer/Prometheus)  │   │
│  └─────────────────┘  └──────────────────┘  └────────────────────────────┘   │
└────────────────────────────────────┬─────────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼─────────────────────────────────────────┐
│                       STORAGE LAYER                                           │
│                                                                                │
│  ┌──────────────────────────────────────┐  ┌───────────────────────────────┐  │
│  │           RocksDB (JNI)              │  │    In-Process Dispatch Queue  │  │
│  │  CF: credentials, acl_rules,         │  │  (Disruptor ring buffer or    │  │
│  │       auth_providers, admin_settings │  │   LinkedBlockingQueue)        │  │
│  │  Path: /data/rocksdb (volume-mount)  │  │                               │  │
│  └──────────────────────────────────────┘  └───────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | Implementation Approach |
|-----------|----------------|------------------------|
| MqttSessionHandler | First touch per channel: decodes MQTT frames, checks rate limits, dispatches to actor | Netty inbound handler, one instance per channel |
| ClientActor | Owns the lifecycle of one MQTT client connection — CONNECT through DISCONNECT. Serializes all processing per clientId. | Custom actor model (from `common/actor`); actor mailbox holds queued messages |
| SubscriptionTrie | Stores all active subscriptions, matches topic strings against filters including `+` and `#` wildcards | `ConcurrentMapSubscriptionTrie` — trie with `ConcurrentHashMap` nodes; `ReadWriteLock` for trie-wide operations |
| RetainedMessageTrie | Stores the latest retained message per topic; supports wildcard lookup on SUBSCRIBE | Same trie structure as subscriptions; in-memory only in R1 |
| MsgDispatcherService | Receives published messages; looks up subscribers via trie; routes to connected sessions | In-process: Disruptor ring buffer (preferred) or `LinkedBlockingQueue` |
| AuthService | Validates CONNECT credentials (username/password, X.509 cert) against stored credentials; evaluates ACL rules per PUBLISH/SUBSCRIBE | Reads from RocksDB; caches hot entries in Caffeine |
| QoSTracker | Tracks in-flight packet IDs for QoS 1 and QoS 2; manages PUBACK/PUBREC/PUBREL/PUBCOMP state | Per-session `ConcurrentHashMap<packetId, InFlightMsg>` inside actor state |
| SessionService | Tracks all connected sessions (clientId → session context); detects duplicate clientId connections | `ConcurrentHashMap`; in-memory only in R1 |
| LwtService | Stores Last Will messages at CONNECT time; publishes them on unexpected disconnect | In-memory `ConcurrentHashMap<clientId, WillMessage>` |
| InProcessCache | Caches credentials and ACL rules to avoid repeated RocksDB reads on hot paths | Caffeine — size-bounded, TTL-based eviction |
| RocksDB storage | Persists across restarts: credentials, ACL rules, auth provider config, admin settings | RocksDB via JNI (`rocksdbjni`); column families per entity type |
| MetricsService | Exposes `/actuator/prometheus` endpoint; counts connections, message rates, errors | Micrometer + `micrometer-registry-prometheus` |
| Netty Bootstrap | Binds TCP/TLS/WS/WSS listeners; manages boss/worker `EventLoopGroup`s | Spring lifecycle bean, started on `ApplicationReadyEvent` |

---

## Recommended Project Structure

```
tbmq-lightweight/
├── application/                          # Main Spring Boot module
│   └── src/main/java/org/thingsboard/mqtt/broker/
│       ├── TbmqLightweightApplication.java
│       ├── server/                       # Netty bootstraps (TCP, TLS, WS, WSS)
│       ├── actors/                       # ClientActor, ActorSystem lifecycle
│       ├── service/
│       │   ├── auth/                     # AuthService backed by RocksDB
│       │   ├── mqtt/
│       │   │   ├── connect/              # CONNECT handling, session init
│       │   │   ├── publish/              # PUBLISH handling, dispatch
│       │   │   ├── subscribe/            # SUBSCRIBE/UNSUBSCRIBE handling
│       │   │   ├── retain/               # Retained message trie
│       │   │   ├── will/                 # LWT storage + publish on disconnect
│       │   │   ├── delivery/             # Message delivery to Netty channel
│       │   │   └── qos/                  # QoS 1/2 in-flight tracker
│       │   ├── processing/               # In-process dispatch (Disruptor/queue)
│       │   ├── subscription/             # ConcurrentMapSubscriptionTrie
│       │   ├── session/                  # In-memory session registry
│       │   └── stats/                    # Metrics collection
│       ├── storage/
│       │   └── rocksdb/                  # RocksDB wrapper (open, CF handles, CRUD)
│       ├── cache/                        # Caffeine in-process cache config
│       └── config/                       # Spring @Configuration classes
│
├── common/                               # Extracted shared modules (see below)
│   ├── actor/                            # Re-used from main TBMQ: TbActorSystem
│   ├── data/                             # Re-used: shared data models (ClientSession, etc.)
│   ├── stats/                            # Re-used: StatsFactory, Micrometer wiring
│   └── util/                             # Re-used: JacksonUtil, ThingsBoardExecutors
│
├── docker/
│   └── Dockerfile                        # Multi-arch JVM + RocksDB native libs
└── pom.xml                               # Maven root: application + common modules
```

### Structure Rationale

- **application/** mirrors the package layout of standard TBMQ to minimize cognitive overhead when reading both codebases side by side.
- **storage/rocksdb/** is isolated from service layer — services call a `StorageService` interface; the RocksDB implementation is a single Spring bean hidden behind it. This makes future storage backend substitution trivial.
- **common/** modules are the candidates for extraction to a shared Maven artifact; keeping them as local modules first avoids premature publishing overhead.
- **service/processing/** is its own sub-package because the in-process dispatch queue is a critical seam — it will need to be swapped or tuned independently of the rest.

---

## Architectural Patterns

### Pattern 1: Actor-Per-ClientId (inherited from TBMQ)

**What:** Every MQTT `clientId` maps to exactly one `ClientActor` instance. All messages for that client — CONNECT, PUBLISH, SUBSCRIBE, DISCONNECT, keep-alive timeouts — are enqueued in that actor's mailbox and processed sequentially. No locks needed inside the actor.

**When to use:** When state per client is complex (QoS tracking, session flags, LWT storage, backpressure counters) and concurrent access from multiple transport threads would require locking.

**Trade-offs:**
- Pro: Eliminates per-client locks entirely; state mutations are single-threaded per clientId.
- Pro: Natural isolation — a bug in one actor doesn't directly affect another.
- Con: Unbounded actor count; actor lifecycle management (creation, idle cleanup) requires care.
- Con: If actor mailbox fills up, new messages for that client are rejected — need backpressure handling at the transport layer.

**From existing TBMQ:** `common/actor/src/main/java/org/thingsboard/mqtt/broker/actors/TbActorSystem.java` is reusable as-is. The `ClientActor` itself needs a lighter adaptation — strip out Kafka-based session event coordination, replace with direct in-process calls.

### Pattern 2: Concurrent Map Subscription Trie

**What:** A trie where each node is a `ConcurrentHashMap<String, TrieNode>`. Topic levels are the keys at each node. Wildcard nodes (`+` and `#`) are special keys in the map. On SUBSCRIBE, walk the trie inserting nodes. On PUBLISH, walk the trie collecting matching subscriptions at each node that matches the topic segment or holds a wildcard.

**When to use:** Always for MQTT topic matching. Hash-based lookup fails for wildcards; bitmaps require a closed topic space; the trie provides a natural balance.

**Trade-offs:**
- Pro: `ConcurrentHashMap` nodes enable concurrent reads without a global lock.
- Pro: Shared topic-level prefixes reduce memory usage.
- Con: `clearEmptyNodes()` still requires a write lock — avoid scheduling it too frequently under high churn.
- Con: Trie depth proportional to topic depth; deeply nested topics (10+ levels) increase traversal cost.

**From existing TBMQ:** `ConcurrentMapSubscriptionTrie` can be reused with minimal modification. Remove Kafka-backed persistence callbacks; the lightweight version manages subscriptions entirely in-memory.

### Pattern 3: In-Process Dispatch Queue (replaces Kafka)

**What:** Published messages are placed onto an in-process queue. A pool of consumer threads dequeues them, calls `SubscriptionTrie.getSubscriptions(topic)`, and directly delivers to each matching active session via its Netty `ChannelHandlerContext.writeAndFlush()`.

**When to use:** Single-node only. Removes Kafka's serialization, network RTT, and partition assignment overhead.

**Options:**

| Implementation | Throughput | Latency | Complexity |
|----------------|-----------|---------|------------|
| `LinkedBlockingQueue` | ~5M msg/s (single node) | ~10-50 µs | LOW — Java stdlib |
| LMAX Disruptor (single producer) | ~80M ops/s | ~1-3 µs | MEDIUM — ring buffer API |
| LMAX Disruptor (multi-producer) | ~29M ops/s | ~3-10 µs | MEDIUM |

**Recommendation:** Start with `LinkedBlockingQueue` wrapped behind a `PublishMsgQueueFactory` interface. If benchmarks show it bottlenecking at target scale (tens of thousands of concurrent connections), swap to Disruptor without changing callers. The `BlockingWaitStrategy` on Disruptor provides conservative CPU usage appropriate for a server process sharing a single node.

**Trade-offs (Disruptor):**
- Pro: 3x+ throughput over queue; cache-friendly ring buffer; pre-allocated event objects reduce GC pressure.
- Con: Fixed ring buffer size must be a power of 2; requires careful capacity planning. Multi-producer mode needed if multiple Netty worker threads publish directly.

### Pattern 4: RocksDB Column Families for Durable State

**What:** A single RocksDB database with one column family per entity type. All durable state (credentials, ACL rules, auth provider config, admin settings) is stored here. Services read RocksDB on startup and on cache miss; writes are synchronous.

**Column family layout:**

| Column Family | Key | Value |
|---------------|-----|-------|
| `credentials` | `clientId` or `username` (bytes) | JSON-serialized credential object |
| `acl_rules` | `clientId:topicFilter` (composite bytes) | JSON-serialized ACL rule |
| `auth_providers` | `providerId` (bytes) | JSON-serialized provider config |
| `admin_settings` | `settingKey` (bytes) | JSON-serialized settings object |

**When to use:** When state must survive process restarts but the workload is key-value (not relational). Credentials and ACLs fit naturally — they are point-lookups keyed by clientId/username.

**Trade-offs:**
- Pro: No external process; files live under `/data/rocksdb` which maps to a Docker volume.
- Pro: Column families enable separate compaction tuning per entity type.
- Con: JNI dependency — must bundle platform-specific native libraries in Docker image; multi-arch build required.
- Con: Schema evolution requires custom migration logic (no SQL migrations); bump a version key in a `metadata` column family.

**Initialization pattern:**
```java
// Always call before any database operations
RocksDB.loadLibrary();

List<ColumnFamilyDescriptor> cfDescriptors = List.of(
    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
    new ColumnFamilyDescriptor("credentials".getBytes()),
    new ColumnFamilyDescriptor("acl_rules".getBytes()),
    new ColumnFamilyDescriptor("auth_providers".getBytes()),
    new ColumnFamilyDescriptor("admin_settings".getBytes())
);
List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
DBOptions dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
RocksDB db = RocksDB.open(dbOptions, "/data/rocksdb", cfDescriptors, cfHandles);
```

**Critical:** Column family handles must be closed before the database. Wrap in a Spring `@PreDestroy` method with correct destruction order.

### Pattern 5: Caffeine In-Process Cache (replaces Redis)

**What:** Caffeine wraps RocksDB reads for hot paths: credential lookup on CONNECT (potentially thousands of connects/s), ACL evaluation on every PUBLISH and SUBSCRIBE.

**Cache design:**

| Cache | Key | TTL | Max Size |
|-------|-----|-----|----------|
| `credentials` | username/clientId | 15 min (configurable) | 10,000 entries |
| `acl_rules` | clientId | 5 min (configurable) | 10,000 entries |
| `auth_providers` | providerId | no eviction (small set) | 100 entries |

**When to use:** For read-heavy, rarely-changing data where RocksDB read latency (~10-100 µs) would be visible on the hot path.

**Trade-offs:**
- Pro: Zero network overhead; no external process; Caffeine's W-TinyLFU eviction provides near-optimal hit rates.
- Con: Cache is local to the JVM — valid only because this is a single-node broker. Would need replacement with distributed cache if clustering is ever added.
- Con: Cache invalidation on credential update requires explicit `Cache.invalidate(key)` calls in the write path.

### Pattern 6: QoS State Machine Per Session

**What:** Each connected session maintains an in-memory map of `packetId → InFlightMessage`. The QoS state machine tracks transitions:

```
QoS 1 Publisher:
  PUBLISH sent → wait for PUBACK
  timeout → retransmit with DUP=true
  PUBACK received → remove packetId

QoS 2 Publisher:
  PUBLISH sent (store message) → wait for PUBREC
  PUBREC received → send PUBREL, store PUBREC state
  PUBCOMP received → remove packetId

QoS 2 Receiver (broker side):
  PUBLISH received → send PUBREC, store packetId (dedup)
  PUBREL received → send PUBCOMP, deliver to subscribers, remove packetId
```

**In-flight tracking structure:**
```java
// Per-session state inside ClientActor
Map<Integer, InFlightMsg> qos1InFlight;   // packetId → stored message
Set<Integer> qos2ReceivedIds;             // for dedup on broker receive side
Map<Integer, InFlightMsg> qos2PubRelWait; // packetId → waiting for PUBCOMP
```

**Packet ID range:** 1–65535 per connection. The broker must track which IDs are in use and refuse reuse until the prior exchange completes (MQTT spec §4.1).

**R1 scope:** QoS 1 and QoS 2 delivery to *currently connected* subscribers is in-memory only. If a subscriber disconnects before receiving a QoS 1/2 message, the message is dropped. Offline queuing is R2.

### Pattern 7: Auth/ACL Plugin Architecture (AuthProvider Chain)

**What:** An ordered chain of `MqttAuthProvider` implementations is consulted on CONNECT. Each provider can ACCEPT, DENY, or SKIP. The first non-SKIP result wins. ACL evaluation uses `AuthorizationRuleService` which evaluates regex-based topic patterns.

**In the lightweight version:** The provider chain simplifies to:
1. `BasicCredentialAuthProvider` — username/password lookup in RocksDB, bcrypt comparison.
2. `SslCertAuthProvider` — X.509 subject CN lookup in RocksDB (only if TLS listener is active).
3. `AllowAnonymousProvider` — configurable; allows connections with no credentials if enabled.

**ACL evaluation:** Topic filters stored as `clientId:topicFilter` in RocksDB. On PUBLISH and SUBSCRIBE, evaluate the client's rules for the given topic using `AuthorizationRuleService.evalTopicPermission()` (already implemented in TBMQ — reuse directly).

**Trade-off:** The heavyweight auth providers (HTTP, SCRAM, JWT) in standard TBMQ require external services or complex protocol flows. For R1 lightweight, include Basic and SSL only. The chain interface allows adding others in R2+ without architectural change.

### Pattern 8: Graceful Shutdown Sequence

**What:** An ordered shutdown that drains in-flight work before closing listeners.

**Sequence:**
```
1. SIGTERM received (Spring ContextClosedEvent)
2. Stop accepting new connections: unbind all Netty server channels
3. Send DISCONNECT (reason: Server shutting down) to all connected clients
   — or — wait for keep-alive timeouts to naturally expire clients
4. Wait for in-process dispatch queue to drain (timeout: configurable, default 5s)
5. Flush and close RocksDB
6. Close Netty EventLoopGroups (gracefulShutdown with quietPeriod)
7. Spring context closes remaining beans
```

**Key implementation point:** RocksDB close must happen *after* all service beans that use it are stopped. Use `@DependsOn` or `@PreDestroy` ordering via Spring's `SmartLifecycle` with ordered phases.

**Anti-pattern to avoid:** Closing RocksDB first and then having service beans attempt writes during their own shutdown — this causes JNI segfaults.

### Pattern 9: Metrics Collection

**What:** Standard Spring Boot Actuator + Micrometer + Prometheus registry pattern. Custom counters and gauges are registered via `MeterRegistry`.

**Key metrics for MQTT broker:**

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `mqtt.connections.active` | Gauge | — | Current connected client count |
| `mqtt.connections.total` | Counter | — | Lifetime connection attempts |
| `mqtt.messages.published` | Counter | `qos` | Messages received from publishers |
| `mqtt.messages.delivered` | Counter | `qos` | Messages sent to subscribers |
| `mqtt.messages.dropped` | Counter | `reason` | Messages dropped (no subscriber, queue full) |
| `mqtt.subscriptions.active` | Gauge | — | Total active subscription count |
| `mqtt.auth.success` | Counter | `provider` | Successful authentications |
| `mqtt.auth.failure` | Counter | `provider`, `reason` | Failed authentications |
| `rocksdb.read.latency` | Timer | `cf` | RocksDB get latency per column family |
| `queue.size` | Gauge | — | Current dispatch queue depth |

**Endpoint:** `GET /actuator/prometheus` — scraped by Prometheus. Enabled via:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

### Pattern 10: Shared Code Extraction Strategy

**What:** The lightweight repo starts by copying modules from the main TBMQ repo. If the drift becomes costly, extract a `tbmq-common` Maven artifact published to `repo.thingsboard.io`.

**Candidate modules for extraction:**

| Module | Extractable? | Condition |
|--------|-------------|-----------|
| `common/actor` | YES, immediately | No Kafka/Redis/PSQL deps; pure actor framework |
| `common/data` | YES, with trimming | Remove Kafka/Redis/Postgres-specific DTOs; keep MQTT models |
| `common/stats` | YES, immediately | Micrometer wiring only |
| `common/util` | YES, immediately | Pure utilities |
| `ConcurrentMapSubscriptionTrie` | YES, with extraction | Extract from `application/` into a library |
| `service/auth` | Partial | Strip HTTP/SCRAM/JWT providers; keep Basic/SSL core |
| `actors/client/ClientActor` | Partial — adapt | Remove Kafka session event coordination |

**Strategy:**
1. In R1: local module copies. Move fast, tolerate some divergence.
2. After R1 ships: extract stable, non-infrastructure modules to `tbmq-protocol-core` artifact if duplication is painful.
3. Never share infrastructure modules (queue, DAO, cache) — they differ by design.

**Versioning:** Shared artifacts must be versioned independently of both repos. Use a `protocol-core.version` property in both root POMs.

---

## Data Flow

### PUBLISH Message Flow (QoS 0)

```
MQTT Client (TCP)
    │
    ▼
Netty EventLoop thread
 → MqttDecoder decodes frame
 → MqttSessionHandler.channelRead()
    → rate-limit check (in-process token bucket)
    → ClientMqttActorManager.processMqttMsg(PUBLISH_MSG)
    │
    ▼
ClientActor mailbox (async enqueue)
    │
ClientActor thread (actor dispatcher)
 → MqttMessageHandler.processMqttMsg(PUBLISH_MSG)
    → topic validation
    → ACL check: AuthorizationRuleService.evalPublishPermission(clientId, topic)
       → Caffeine cache hit → return
       → Caffeine miss → RocksDB read → cache write → return
    → MsgDispatcherService.dispatchMessage(PublishMsg)
    │
    ▼
In-process dispatch queue (Disruptor / BlockingQueue)
    │
Consumer thread pool
 → SubscriptionTrie.getSubscriptions(topic)         [concurrent read, no lock]
 → for each matching subscription:
     → SessionService.getSessionCtx(clientId)
     → if session active on this node:
         → MqttMsgDeliveryService.deliverMsg(session, msg)
            → channel.writeAndFlush(MqttPublishMessage)
     → if no active session: drop (QoS 0, no offline queue in R1)
```

### CONNECT Flow

```
MqttSessionHandler.channelRead(CONNECT)
    │
    ▼ (synchronous, still on Netty thread — keep fast)
Extract ClientId, credentials
    │
    ▼
ClientMqttActorManager.initSession(clientId, sessionCtx)
  → find or create ClientActor for clientId
    │
    ▼
ClientActor.doProcess(SESSION_INIT_MSG)
  → AuthService.authenticate(credentials)
     → BasicCredentialAuthProvider.authenticate()
        → Caffeine.get(username) → if miss: RocksDB.get(credentials CF)
        → BCrypt.verify(password, hashedPassword)
  → if auth fails: send CONNACK(REFUSED) + close channel
  → if auth success:
     → SessionService.registerSession(clientId, sessionCtx)
     → check for existing session: if yes, disconnect old session
     → LwtService.storeWill(clientId, willMsg)   [if will present in CONNECT]
     → send CONNACK(ACCEPTED)
     → actor transitions to CONNECTED state
```

### SUBSCRIBE Flow

```
ClientActor.doProcess(MQTT_SUBSCRIBE_MSG)
  → for each TopicFilter in SUBSCRIBE:
     → ACL check: AuthorizationRuleService.evalSubscribePermission(clientId, topicFilter)
     → SubscriptionTrie.addSubscription(clientId, topicFilter, qos, sessionCtx)
  → send SUBACK with granted QoS levels
  → for each accepted topicFilter:
     → RetainedMessageTrie.getRetainedMessages(topicFilter)
     → for each retained message: deliver immediately to client
```

### Disconnect / Unexpected Close Flow

```
Clean DISCONNECT (packet received):
  ClientActor.doProcess(MQTT_DISCONNECT_MSG)
    → LwtService.removeWill(clientId)         [suppress LWT — clean disconnect]
    → SubscriptionTrie.removeAllSubscriptions(clientId)
    → SessionService.unregisterSession(clientId)
    → close Netty channel

Unexpected close (channelInactive / keep-alive timeout):
  MqttSessionHandler.channelInactive()
    → ClientMqttActorManager.processMqttMsg(CHANNEL_INACTIVE_MSG)
    → ClientActor.doProcess(CHANNEL_INACTIVE_MSG)
       → LwtService.publishWill(clientId)      [send will to dispatch queue]
       → SubscriptionTrie.removeAllSubscriptions(clientId)
       → SessionService.unregisterSession(clientId)
```

---

## Component Boundaries

```
[Netty Transport] — one way → [Actor Layer]
  Contract: ClientMqttActorManager interface
  Data: actor message types (SESSION_INIT_MSG, MQTT_PUBLISH_MSG, etc.)
  Thread boundary: Netty EventLoop → Actor dispatcher (async enqueue)

[Actor Layer] — calls → [Service Layer]
  Contract: typed service interfaces (AuthService, SubscriptionService, etc.)
  Data: domain objects (ClientSessionCtx, PublishMsg, Subscription)
  Thread: Actor dispatcher thread (synchronous service calls acceptable)

[Service Layer: MsgDispatcherService] — async → [Dispatch Queue]
  Contract: PublishMsg enqueue
  Thread boundary: Actor dispatcher → queue consumer threads

[Dispatch Queue consumers] — reads → [SubscriptionTrie]
  Contract: SubscriptionTrie.getSubscriptions(topic)
  Thread: queue consumer pool (concurrent reads, ConcurrentHashMap nodes)

[Dispatch Queue consumers] — writes → [Netty channels]
  Contract: channel.writeAndFlush(MqttMessage)
  Thread: queue consumer → Netty EventLoop (safe: writeAndFlush is thread-safe)

[Service Layer: AuthService] — reads → [Caffeine cache]
  Contract: Cache.get(key, loader)
  Thread: Actor dispatcher (blocking RocksDB read on cache miss — acceptable)

[Caffeine cache loader] — reads → [RocksDB]
  Contract: RocksDB.get(cfHandle, key)
  Thread: Actor dispatcher (on cache miss path — minimize misses via warm-up)
```

---

## Suggested Build Order

Components have clear dependency edges. Build in this order:

### Phase 1 — Foundation (enable any test to run)
1. **RocksDB storage wrapper** (`storage/rocksdb/`) — column families, CRUD, startup/shutdown lifecycle.
2. **In-process cache** (`cache/`) — Caffeine configuration, cache specs per entity type.
3. **Actor system** (copy `common/actor/`) — TbActorSystem, TbActorMailbox, dispatchers.
4. **Common data models** (copy/trim `common/data/`) — strip Kafka-specific DTOs.

### Phase 2 — Core Protocol
5. **Subscription trie** (`service/subscription/`) — copy `ConcurrentMapSubscriptionTrie`, adapt to remove Kafka callbacks.
6. **Session service** (`service/session/`) — in-memory session registry.
7. **QoS tracker** (`service/qos/`) — in-flight packet ID maps per session.
8. **Retained message trie** (`service/mqtt/retain/`) — copy `ConcurrentMapRetainMsgTrie`.

### Phase 3 — Auth
9. **Auth service** (`service/auth/`) — BasicCredential + SSL providers backed by RocksDB/Caffeine.
10. **ACL service** (`service/auth/`) — `AuthorizationRuleService`, regex-based topic evaluation.

### Phase 4 — Transport
11. **Netty bootstraps** (`server/`) — TCP, TLS, WS, WSS; copy channel initializers, strip proxy protocol if not needed.
12. **MqttSessionHandler** — adapt to remove Kafka-based rate limiting; replace with in-process token bucket.
13. **ClientActor** — adapt from standard TBMQ; remove Kafka session event coordination; wire direct service calls.

### Phase 5 — Message Routing
14. **In-process dispatch queue** (`service/processing/`) — implement `PublishMsgQueueFactory` with `LinkedBlockingQueue` backend first.
15. **MsgDispatcherService** — wire trie lookup + direct delivery; test fan-out.
16. **LWT service** (`service/mqtt/will/`) — store on CONNECT, publish on unexpected disconnect.

### Phase 6 — REST API + Metrics
17. **Metrics** (`service/stats/`) — Micrometer counters/gauges; `/actuator/prometheus` endpoint.
18. **REST API** (minimal) — credential CRUD, ACL CRUD, session list (read-only), health endpoint. No Swagger fork needed — use standard springdoc.

### Phase 7 — Docker + Integration
19. **Dockerfile** — multi-arch base image; include RocksDB native libs for x86 and ARM.
20. **Integration tests** — Paho/HiveMQ client tests for connect/publish/subscribe/QoS flows.

---

## Scaling Considerations

| Concern | At 1K connections | At 10K connections | At 100K connections |
|---------|------------------|-------------------|---------------------|
| Subscription trie | In-memory, trivial | ~50 MB trie; fine | ~500 MB; GC pressure on `clearEmptyNodes()` — tune scheduling |
| Dispatch queue | Single queue sufficient | Single queue + 4-8 consumer threads | Consider partitioned queues by topic hash |
| Actor dispatcher | 10 threads sufficient | 20-50 threads | Actor pool pressure; tune dispatcher thread count |
| RocksDB reads | Only on cache miss; negligible | Cache hit rate ~99% at steady state | Ensure Caffeine max-size exceeds active credential count |
| Netty workers | Default (2*cores) | Default sufficient | May need to increase worker thread count (`WORKER_GROUP_THREADS`) |
| Per-connection QoS state | Tiny (small map per session) | ~20 MB total | ~200 MB total; tune `receiveMaximum` (MQTT 5) to cap in-flight |

### Scaling Priorities

1. **First bottleneck:** Dispatch queue consumer threads — tune count based on subscriber fan-out ratio. If one message has 1,000 subscribers, each delivery is a `writeAndFlush` I/O call; consumer threads must be sized accordingly.
2. **Second bottleneck:** Subscription trie `clearEmptyNodes()` write lock — fires when subscriptions are removed. Run the cleanup cron less frequently under heavy churn. Consider a background thread instead of a scheduled task.
3. **Third bottleneck:** Netty `writeAndFlush` from non-EventLoop threads — these are submitted as tasks to the channel's EventLoop and are safe but add latency. Pre-encode `ByteBuf` objects before submitting to EventLoop when possible.

---

## Anti-Patterns

### Anti-Pattern 1: Performing RocksDB Writes on Netty I/O Threads

**What people do:** Call `RocksDB.put()` inside a Netty handler directly (e.g., on CONNECT, write credentials to RocksDB synchronously on the EventLoop thread).

**Why it's wrong:** RocksDB writes involve disk I/O (even with memtable buffering, occasional flushes). Blocking the Netty EventLoop thread stalls *all* connections sharing that EventLoop.

**Do this instead:** Offload credential writes to a dedicated thread pool (Spring `@Async` or `ExecutorService`). For credential management (infrequent writes via REST API), a small fixed-thread-pool is fine.

### Anti-Pattern 2: Sharing a Single `WriteOptions` or `ReadOptions` Object Across Threads

**What people do:** Create one `WriteOptions` / `ReadOptions` and share it across all RocksDB calls for efficiency.

**Why it's wrong:** RocksDB's `WriteOptions` and `ReadOptions` are not thread-safe for concurrent modification. While reads usually share the same options safely, modifying them concurrently causes native crashes.

**Do this instead:** Create options objects once at startup with desired settings and never mutate them. Or create per-call options when configuration varies.

### Anti-Pattern 3: Calling `channel.writeAndFlush()` from Multiple Threads Without Ordering

**What people do:** Multiple dispatch queue consumer threads each call `channel.writeAndFlush()` for the same subscriber simultaneously (possible when the client has many matching subscriptions from concurrent publishes).

**Why it's wrong:** While Netty's `writeAndFlush()` is thread-safe in the sense that it won't corrupt the buffer, the *ordering* of writes submitted from multiple threads to the same channel is non-deterministic. MQTT requires messages to be delivered in order relative to QoS level.

**Do this instead:** For QoS 0 (best-effort), multi-thread delivery is acceptable — MQTT spec does not guarantee ordering for QoS 0 across different publishers. For QoS 1/2, ensure that delivery to a given subscriber is serialized — either route QoS messages through the actor (which is single-threaded per clientId) or use a per-subscriber delivery queue.

### Anti-Pattern 4: Closing RocksDB Before Spring Beans That Depend On It

**What people do:** Register RocksDB as a simple `@Bean` without explicit lifecycle ordering; Spring closes it based on bean dependency graph which may not match shutdown order.

**Why it's wrong:** Any service bean still executing in its `@PreDestroy` that calls a RocksDB method after the DB is closed will cause a JNI segfault, crashing the JVM ungracefully.

**Do this instead:** Implement `SmartLifecycle` on the `RocksDbService` bean with a high `getPhase()` value (i.e., it stops last among infrastructure beans). All service beans that use RocksDB must stop first.

### Anti-Pattern 5: Treating the In-Process Queue as Durable

**What people do:** Assume that messages in the `LinkedBlockingQueue` or Disruptor ring buffer will survive a process crash or graceful shutdown.

**Why it's wrong:** They do not. Both are in-memory structures. Any messages enqueued but not yet delivered are lost on shutdown.

**Do this instead:** Accept this as a design constraint for R1 (documented in PROJECT.md: no persistence in R1). In the graceful shutdown sequence, drain the queue before closing listeners. Document the "at most once" delivery guarantee during shutdown windows.

### Anti-Pattern 6: Using `ConcurrentMapSubscriptionTrie` for Retained Message Storage Without Empty-Node Cleanup

**What people do:** Re-use the subscription trie structure for retained messages but neglect to schedule the `clearEmptyNodes()` cleanup.

**Why it's wrong:** When retained messages are deleted (by publishing an empty payload to a topic), the trie node remains with a null payload. Over time, this leads to unbounded memory growth in brokers with high retained message churn.

**Do this instead:** Schedule `clearEmptyNodes()` on a cron (e.g., every 15 minutes at off-peak hours). Make the schedule configurable. Alert if retained message trie node count grows without a corresponding increase in non-null payloads.

---

## Integration Points

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| Netty transport → Actor layer | `ClientMqttActorManager` interface; async actor message enqueue | Netty thread must return immediately; never block waiting for actor |
| Actor layer → Service layer | Direct synchronous calls | Actor is already off the Netty thread; blocking is acceptable for short operations (cache reads) |
| Service layer → RocksDB | Synchronous `RocksDB.get/put/delete` | Only for reads; writes for credential management via REST API thread pool |
| Service layer → Dispatch queue | `queue.put(msg)` or `disruptor.publishEvent()` | Producer is actor thread; consumer pool is separate |
| Dispatch queue consumers → Netty channels | `channel.writeAndFlush(msg)` | Thread-safe; tasks submitted to channel's EventLoop |
| REST API → Service layer | Standard Spring `@Autowired` service calls | REST API thread pool; same JVM, no serialization overhead |

### External Interfaces

| Interface | Pattern | Notes |
|-----------|---------|-------|
| MQTT TCP (1883) | Netty NIO server | Boss group: 1 thread; worker group: 2*CPU threads (configurable) |
| MQTT TLS (8883) | Netty NIO + SslHandler | Certificate path configurable via env; `netty-handler` SslContext |
| MQTT WS (8084) | Netty WebSocket + MQTT-over-WS | `WebSocketServerProtocolHandler` + `MqttDecoder` in pipeline |
| REST API (8083) | Spring Boot embedded Tomcat | Credential CRUD, session management, health |
| Prometheus metrics | Spring Actuator `/actuator/prometheus` | Prometheus-compatible text format via Micrometer |
| Docker volume (`/data`) | RocksDB data directory | Mount path: `/data/rocksdb`; configurable via env var |

---

## Sources

- [TBMQ codebase analysis: `.planning/codebase/ARCHITECTURE.md`] — existing architecture, layer responsibilities, data flows (HIGH confidence — direct code analysis)
- [TBMQ codebase analysis: `.planning/codebase/STRUCTURE.md`] — module boundaries, package layout (HIGH confidence)
- [LMAX Disruptor User Guide](https://lmax-exchange.github.io/disruptor/user-guide/index.html) — producer strategies, wait strategies, performance characteristics (HIGH confidence — official source)
- [LMAX Disruptor technical paper](https://lmax-exchange.github.io/disruptor/disruptor.html) — throughput numbers: single-producer ~80M ops/s, multi-producer ~29M ops/s (HIGH confidence — official)
- [Fast Topic Matching — Brave New Geek](https://bravenewgeek.com/fast-topic-matching/) — trie vs. hashmap vs. inverted bitmap tradeoffs for MQTT (MEDIUM confidence — well-cited community research)
- [HiveMQ: MQTT Topic Tree Matching](https://www.hivemq.com/blog/mqtt-topic-tree-matching-challenges-best-practices-explained/) — wildcard matching challenges and best practices (HIGH confidence — official HiveMQ documentation)
- [RocksJava Basics — facebook/rocksdb Wiki](https://github.com/facebook/rocksdb/wiki/RocksJava-Basics) — Java API patterns, column family usage, initialization order (HIGH confidence — official)
- [RocksDB Column Families Wiki](https://github.com/facebook/rocksdb/wiki/column-families) — column family design, atomic cross-CF writes (HIGH confidence — official)
- [HiveMQ: MQTT QoS Levels](https://www.hivemq.com/blog/mqtt-essentials-part-6-mqtt-quality-of-service-levels/) — QoS state machine flows (HIGH confidence — protocol documentation)
- [HiveMQ: MQTT LWT](https://www.hivemq.com/blog/mqtt-essentials-part-9-last-will-and-testament/) — LWT timing and clean vs. unexpected disconnect behavior (HIGH confidence)
- [Caffeine GitHub](https://github.com/ben-manes/caffeine) — W-TinyLFU eviction policy, in-process caching API (HIGH confidence — official)
- [Netty ChannelPipeline 4.1 API](https://netty.io/4.1/api/io/netty/channel/ChannelPipeline.html) — pipeline handler ordering (HIGH confidence — official)
- [Netty Thread Model](https://netty.io/wiki/thread-model.html) — boss/worker EventLoopGroup design (HIGH confidence — official)
- [Spring Boot Actuator Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html) — Micrometer + Prometheus integration (HIGH confidence — official)
- [BifroMQ Topic Subscription Mechanism](https://bifromq.apache.org/blog/bifromq-topic-subscription/) — concurrent subscription trie (CS-Trie) approach (MEDIUM confidence — project documentation)

---

*Architecture research for: TBMQ Lightweight — embedded MQTT broker*
*Researched: 2026-04-02*
