# Pitfalls Research

**Domain:** Lightweight embedded MQTT broker (Java/JVM, single Docker image, in-process messaging, RocksDB storage)
**Researched:** 2026-04-02
**Confidence:** HIGH (core MQTT protocol pitfalls + Netty + RocksDB verified via official GitHub issues, spec docs, and production broker engineering blogs)

---

## Critical Pitfalls

### Pitfall 1: LWT Fires on Client Takeover (Session Replacement)

**What goes wrong:**
When client A connects with a Will Message, then client B connects using the same ClientID (session takeover), the broker fires client A's LWT. This is wrong — a client takeover is a controlled handoff, not an unexpected disconnection. The spec is ambiguous on this in MQTT 3.1.1, but MQTT 5.0 clarifies that LWT MUST NOT be published when a new connection with the same ClientID is opened before the Will Delay Interval expires.

**Why it happens:**
Implementors treat disconnect processing as a single path without distinguishing "replaced by new connection" from "network failure." The standard disconnect→publish LWT flow fires regardless of disconnect cause.

**How to avoid:**
Track the disconnect reason per session. When a session is replaced by a new CONNECT for the same ClientID, mark the old session as "displaced" and suppress LWT publication. In MQTT 5.0, honor the Will Delay Interval — defer LWT publication until the interval expires and confirm no new session has taken over.

**Warning signs:**
- LWT subscribers receive spurious "offline" notifications during reconnect floods
- Integration tests for reconnect scenarios show false LWT delivery

**Phase to address:**
Core protocol engine phase (session + connect/disconnect handling). This is a spec-correctness bug that surfaces during integration testing, not performance testing.

---

### Pitfall 2: QoS 2 State Lost on In-Process Restart (Phantom PUBREL)

**What goes wrong:**
QoS 2 flow requires four packet exchanges: PUBLISH → PUBREC → PUBREL → PUBCOMP. The "exactly once" guarantee requires the broker to persist the "received PUBLISH, awaiting PUBREL" state. If this state is held only in heap memory and the broker restarts (or the connection drops), the client will retransmit the PUBLISH with the DUP flag set. If the broker does not recognize the packet identifier as already received, it delivers the message a second time — exactly once is violated.

**Why it happens:**
R1 defers persistent sessions, which makes developers assume QoS 2 in-flight state can also be skipped. But the QoS 2 handshake state (PUBREL awaiting PUBCOMP) is separate from session state — it belongs to the connection-level flow control and must survive at minimum within a single connected session with no duplicates.

**How to avoid:**
Maintain per-connection in-flight QoS 2 state as a `Map<packetId, QoS2State>` in the session object. Within a connected session, deduplicate on re-received DUP PUBLISH by checking the in-flight map. Accept that across restarts (clean session R1), the message may be redelivered — document this explicitly. Never silently discard a retransmitted PUBLISH just because you see DUP=true; only deduplicate when you have a matching pending PUBREL in your in-flight table.

**Warning signs:**
- MQTT clients report double message delivery in QoS 2 flows after reconnection
- EMQX reported a known bug (Feb 2025) where DUP+reconnect caused missed delivery — the same root cause in reverse

**Phase to address:**
QoS protocol phase. Requires a focused state machine test suite covering the full PUBREL/PUBCOMP cycle including connection interruptions.

---

### Pitfall 3: RocksDB JNI Native Memory Exceeds JVM Heap Budget

**What goes wrong:**
RocksDB operates almost entirely outside the JVM heap via JNI. Its block cache, memtables, filter blocks, and index blocks are all native memory. Developers configure `-Xmx` appropriately for the heap but forget that RocksDB can consume an additional 500 MB–2 GB+ of RSS depending on block cache size and compaction work. At 10K+ concurrent connections with RocksDB backing credentials and ACLs, the container OOMs even though heap metrics look healthy.

**Why it happens:**
Java developers are trained to think about heap (`-Xmx`). RocksDB's JNI native memory is invisible to JVM heap monitoring tools. The default block cache is 32 MB but index/filter blocks can spill outside it if `cache_index_and_filter_blocks` is false (the default). After RocksDB 7.x removed Java object finalizers, failing to call `close()` on RocksJava objects causes silent native memory leaks that appear as slow RSS growth.

**How to avoid:**
- Set Docker container memory limit to `JVM heap + (RocksDB block cache) + (Netty direct buffers) + 20% overhead`. For a typical small deployment: `1 GB heap + 256 MB RocksDB + 256 MB Netty ≈ 1.7 GB container limit`.
- Set `cache_index_and_filter_blocks = true` in `BlockBasedTableConfig` so index/filter blocks count against the block cache budget.
- Use try-with-resources or explicit `close()` on all RocksDB objects (`RocksDB`, `Options`, `ReadOptions`, `WriteOptions`, iterators). After v7.x, no finalizer saves you.
- Monitor RSS in addition to JVM heap via `/proc/meminfo` or Micrometer's `system.memory.used` gauge.

**Warning signs:**
- Container memory limit reached while JVM heap is under 60% utilization
- RSS grows 50–100 MB per hour under normal load with no corresponding heap growth
- `jemalloc` heap profile shows `rocksdb::UncompressBlockContentsForCompressionType` as top allocator

**Phase to address:**
Infrastructure phase (RocksDB integration). Add a native memory budget test as a first-class acceptance criterion before declaring the phase complete.

---

### Pitfall 4: RocksDB JNI Fails to Load on ARM64 Alpine (musl incompatibility)

**What goes wrong:**
The `rocksdbjni` fat JAR ships two Linux native libraries: `librocksdbjni-linux64.so` (glibc x86_64) and `librocksdbjni-linux-aarch64.so` (glibc arm64). Alpine Linux uses musl libc. On ARM64 Alpine, the native library load fails with `libstdc++.so.6: cannot open shared object file: No such file or directory`, causing broker startup failure. This is documented in RocksDB issue #10651 and multiple Apache project issues (RocketMQ, Flink, Kafka).

**Why it happens:**
Developers reach for Alpine because it produces the smallest Docker images (~5 MB base vs ~190 MB Debian). The glibc vs musl incompatibility is not obvious from the RocksDB documentation, and the error message does not clearly identify the root cause.

**How to avoid:**
Use `eclipse-temurin:17-jre-jammy` (Debian/Ubuntu, glibc) as the Docker base image. This is a hard constraint for R1. Alpine is explicitly off the table while RocksDB JNI is in the stack. If image size is a concern, use `jlink` with a multi-stage build to produce a custom JRE on top of the Jammy base — achievable 50–70 MB JRE vs 190 MB full JRE, while maintaining glibc compatibility.

**Warning signs:**
- CI passes on x86_64 but ARM64 Docker build starts failing at broker startup
- `UnsatisfiedLinkError` or `libstdc++` errors in container logs on any Alpine-based image
- Someone changes the base image from `eclipse-temurin:17-jre-jammy` to `eclipse-temurin:17-jre-alpine` without realizing the constraint

**Phase to address:**
Docker image / infrastructure phase. Lock the base image in the first Dockerfile commit with an explicit comment explaining why Alpine is prohibited.

---

### Pitfall 5: Disruptor Ring Buffer Full Blocks Netty I/O Thread

**What goes wrong:**
The LMAX Disruptor ring buffer is bounded. When the ring buffer fills (producers outpace consumers), the default `BlockingWaitStrategy` blocks the calling thread until space is available. If publish events are submitted to the Disruptor from Netty I/O event loop threads, a full ring buffer causes those threads to block, stalling all I/O on the affected event loop — including heartbeats, ACKs, and new connection accepts. This degrades from a flow control event into a full broker stall.

**Why it happens:**
The Disruptor is designed for dedicated producer threads. Calling `ringBuffer.publishEvent()` from Netty's I/O event loop (which must never block) breaks Netty's non-blocking contract. The failure mode is non-obvious: the broker appears to accept connections but processes no messages.

**How to avoid:**
Never publish to the Disruptor directly from a Netty I/O thread. The publish path must be: Netty I/O thread → decode message → hand off to a bounded non-blocking queue or use `RingBuffer.tryPublishEvent()` (non-blocking, returns false if full). Implement explicit backpressure: if `tryPublishEvent` returns false, trigger Netty channel non-writability (`channel.config().setAutoRead(false)`) to apply TCP backpressure upstream. Resume autoRead when the ring buffer drains below a low watermark.

**Warning signs:**
- All active connections become unresponsive simultaneously under spike publish load
- Netty event loop thread shows as BLOCKED in thread dumps
- Broker CPU drops to near zero during a load spike (blocked threads, not processing)

**Phase to address:**
Message dispatch phase. Must be validated with a load test that spikes above the steady-state publish rate before declaring the dispatch layer complete.

---

### Pitfall 6: Netty ByteBuf Leaks Under Long-Running Load

**What goes wrong:**
Netty uses reference-counted `ByteBuf` objects (pooled by default). Any code path that receives a `ByteBuf` (e.g., in `channelRead`) but forgets to call `release()` or does not pass it to a handler that will release it creates a native memory leak. At 10K+ connections with continuous message flow, even a single unreleased buffer per decoded MQTT packet causes native memory exhaustion within hours. The JVM's garbage collector does not reclaim native ByteBuf memory — only explicit `release()` calls do.

**Why it happens:**
The pattern is subtle: `ByteBuf` must be released by the last handler that touches it. Forking processing (e.g., copying a reference into a session object without retaining) while the pipeline also releases is a common mistake. Exception paths that skip the `finally` block are another common source.

**How to avoid:**
- Enable `io.netty.leakDetection.level=PARANOID` in development/CI environments. This adds a small overhead but catches every leak with a stack trace. Switch to `SIMPLE` in production.
- Use Netty's `SimpleChannelInboundHandler<MqttMessage>` which auto-releases the message buffer after `channelRead0()` returns — removes the release burden from protocol handlers.
- If a `ByteBuf` payload must be retained beyond the handler (e.g., for retained message storage), always call `buf.retain()` before keeping the reference, and ensure the code path that releases it is explicit.
- Set `-XX:MaxDirectMemorySize` equal to the expected Netty direct buffer budget to surface leaks as OOM errors rather than silent RSS growth.

**Warning signs:**
- `LEAK: ByteBuf.release() was not called` warnings in logs (even one per 1000 = leak)
- Direct memory usage growing monotonically under load despite stable heap
- `OutOfMemoryError: Direct buffer memory` after hours of sustained load

**Phase to address:**
Netty integration phase. Run the leak detector for a full 24-hour load test before declaring the networking layer stable.

---

### Pitfall 7: Topic Subscription Trie Requires Read-Write Lock or Copy-on-Write for Concurrent Mutations

**What goes wrong:**
The subscription trie (used for wildcard `+`/`#` matching) is read on every PUBLISH and written on every SUBSCRIBE/UNSUBSCRIBE. With thousands of concurrent connections, a naive `synchronized` block on the trie creates a global lock that becomes the bottleneck above ~5,000 concurrent publishers. A single slow wildcard match (e.g., `#` matching all topics across a large tree) while subscriptions are being modified causes lock contention that tanks publish throughput.

**Why it happens:**
The initial implementation uses a simple lock for correctness, which works at low scale. Developers optimise other parts of the system while the trie lock becomes the invisible ceiling.

**How to avoid:**
Use a concurrent trie structure from the start. Two practical options for Java:
1. `ConcurrentHashMap`-backed trie where each node's children are a `ConcurrentHashMap<String, TrieNode>` — allows concurrent reads with node-level locking
2. Copy-on-write approach: maintain the trie as an immutable snapshot, replace atomically on mutation via `AtomicReference<TrieNode>` — reads are fully lock-free, writes are infrequent enough to absorb copy cost

For the scale target (tens of thousands of connections, not millions), option 1 is simpler and sufficient. The existing TBMQ subscription trie logic may already address this — verify before re-implementing.

**Warning signs:**
- Thread dumps show many threads waiting on the same trie monitor lock
- Publish latency climbs linearly with subscriber count rather than being constant
- Wildcard-heavy deployments (`#` root subscriptions) show disproportionate CPU time in matching

**Phase to address:**
Subscription management phase. Benchmark with 1,000 concurrent subscribers including wildcard patterns at the start of this phase, not at the end.

---

### Pitfall 8: Keep-Alive Timer Fires False Disconnects Under Load

**What goes wrong:**
The MQTT spec requires the broker to disconnect a client that hasn't sent any packet within `1.5 × keepAlive` seconds. Implementing this with `IdleStateHandler` in Netty is straightforward, but the timer fires based on when the last byte was read from the channel — not when the last MQTT packet was fully decoded. Under heavy load where the broker's event loop is congested, a large PUBLISH payload may arrive in multiple TCP fragments, keeping the idle timer alive, while a legitimate PINGREQ may arrive and not be decoded in time due to event loop backlog, causing a false positive disconnect.

Additionally, setting the idle timeout to exactly `1.5 × keepAlive` provides no tolerance. The spec says "server may disconnect" — not that it must do so at exactly 1.5x. A 10–20% grace margin prevents spurious disconnects under transient load spikes.

**How to avoid:**
- Set `IdleStateHandler` reader timeout to `1.5 × keepAlive + grace period` (recommended: 1.75× of client-specified keepAlive, clamped to a sensible maximum e.g. 600 s).
- Track "last MQTT packet received" time at the protocol handler level (updated after a complete MQTT frame is decoded), not at the TCP byte level. Use this timestamp for the keepAlive expiry check.
- For MQTT 5.0: honor the Server Keep Alive property in CONNACK if the broker wants to override the client's proposal.

**Warning signs:**
- Clients report unexpected disconnections without sending DISCONNECT
- Disconnect rate spikes correlate with high publish load rather than client inactivity
- `PINGRESP` latency metrics increase with broker load

**Phase to address:**
Core protocol engine phase. Add an integration test that sends a PINGREQ during sustained publish load to verify it does not trigger false disconnection.

---

### Pitfall 9: WebSocket Subprotocol Header Rejection Breaks Browser MQTT Clients

**What goes wrong:**
MQTT over WebSocket requires the WebSocket handshake to include `Sec-WebSocket-Protocol: mqtt`. If the broker's WebSocket server returns an empty `Sec-WebSocket-Protocol` response header (or omits it), some browsers (notably Chrome) reject the connection with "Sent non-empty 'Sec-WebSocket-Protocol' header but no response was received." This was a documented bug in multiple MQTT broker WebSocket implementations and is a common source of "works in Paho, fails in browser" reports.

**Why it happens:**
Developers test WebSocket MQTT with the Paho Java or Python client (which is lenient about the subprotocol header) but not with browser-based clients like MQTT.js in a web app. The broker's HTTP upgrade handler either doesn't read the incoming `Sec-WebSocket-Protocol` header or doesn't echo it back.

**How to avoid:**
In the Netty WebSocket pipeline, configure `WebSocketServerProtocolHandler` with `subprotocols: "mqtt"` to ensure the negotiated protocol is echoed in the handshake response. Verify by connecting via a browser console using:
```javascript
const client = new Paho.Client('localhost', 8084, '/mqtt', 'clientId');
```
and confirming the WebSocket handshake headers in browser devtools include `Sec-WebSocket-Protocol: mqtt`.

**Warning signs:**
- Browser-based MQTT clients fail to connect with WebSocket handshake errors
- Python/Java Paho clients succeed while `mqttjs` in browser fails
- `Connection refused` errors specific to the WebSocket port from web apps

**Phase to address:**
WebSocket implementation phase. Browser-based test must be part of the acceptance criteria — not just a Paho Java test.

---

### Pitfall 10: TLS Certificate Reload Requires Broker Restart (Zero Downtime Risk)

**What goes wrong:**
TLS certificates have expiry dates (typically 90 days for Let's Encrypt, 1 year for enterprise CAs). If the broker loads the certificate once at startup (standard JVM keystore approach) and does not support runtime certificate reload, certificate rotation requires a broker restart. For an embedded single-node broker, this means a downtime window every certificate rotation cycle. Users who automate certificate rotation via certbot or cert-manager will find the broker restart requirement surprising and break automation pipelines.

**Why it happens:**
The JVM `SSLContext` and Netty's `SslContext` are typically created once at application startup. Certificate rotation is not a concern in short-lived services (Lambda, containers), but a broker is a long-running process where certificate reload is operationally important.

**How to avoid:**
- Use Netty's `SslContext` with an `OpenSslServerContext` (via `netty-tcnative-boringssl-static`) which supports reloading via `SSLContext.reload()` — or implement a handler that recreates the `SslContext` and swaps the `SslHandler` in new connections' pipelines without dropping existing ones.
- For R1, at minimum document the need to restart and ensure the broker starts back up quickly and resumes connections. Target sub-5 second restart.
- For post-R1: implement a `POST /actuator/tls/reload` endpoint that re-reads cert files from the mounted path and updates the SSL context for new connections.

**Warning signs:**
- Certificate expiry monitoring alerts fire but there's no way to rotate without downtime
- `javax.net.ssl.SSLHandshakeException: certificate_expired` errors after 90 days
- CI/CD cert-manager webhook fails because it cannot trigger a broker cert reload

**Phase to address:**
TLS phase. At minimum, document the limitation in the README at R1. Add cert reload as a P2 feature.

---

### Pitfall 11: Packet Identifier Space Exhausted on High-Rate QoS 1/2 Publishers

**What goes wrong:**
MQTT packet identifiers are 16-bit unsigned integers (range 1–65535). If a single client publishes QoS 1/2 messages faster than the broker sends PUBACK/PUBREC responses, it exhausts all 65,535 available identifiers. At this point, the client cannot send new messages until outstanding flows complete. This is not a broker bug per se, but if the broker's PUBACK path is slow (e.g., blocked on RocksDB writes for session state it does not need), it creates artificial backpressure that manifests as throughput collapse at high message rates.

**Why it happens:**
For a clean-session-only R1 broker, there is no need to persist QoS 1 PUBACK state to durable storage before sending the PUBACK. Developers who copy patterns from the standard TBMQ (which must persist state for persistent sessions) may add unnecessary RocksDB writes on the QoS 1 ACK path, creating latency that exhausts the packet ID space at high rates.

**How to avoid:**
For clean-session-only R1: send PUBACK immediately upon receiving a valid QoS 1 PUBLISH, without any persistence call. The "at least once" guarantee is satisfied by the client retransmitting until it gets a PUBACK. Only persist to RocksDB for data that must survive restarts (credentials, ACLs). Keep the hot QoS 1 publish path entirely in memory.

**Warning signs:**
- Publisher throughput plateaus at a fraction of hardware capability
- PUBACK latency increases linearly with publish rate
- Thread profiles show QoS 1 ACK path spending time in RocksDB write operations

**Phase to address:**
Message dispatch + QoS handling phase. Benchmark single-client QoS 1 publish throughput early as a baseline metric. Target: PUBACK round-trip < 1 ms at 10K msg/sec.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| `LinkedBlockingQueue` instead of Disruptor for dispatch | Simpler implementation, fewer moving parts | Throughput ceiling ~2–3M ops/sec under contention; may hit limit at 10K+ concurrent publishers | Acceptable for R1 if kept behind an interface; swap to Disruptor when benchmarks show saturation |
| In-memory-only retained messages | No storage code to write | Lost on restart; users discover this at 3 AM after a container restart | Acceptable for R1 with explicit documentation; add RocksDB backing in R2 |
| Hard-coded Netty watermark defaults | No config surface to expose | Default 64 KB high watermark may be too low for large message payloads (retained messages can be up to 256 MB per spec) | Acceptable for R1 if configurable via env vars from the start |
| Restart-required TLS cert rotation | No cert-reload code to write | Every cert expiry requires planned downtime; breaks cert-manager automation | Acceptable for R1 with documentation; must be addressed before production-grade release |
| Single RocksDB `WriteOptions` for all writes | Simpler; no write tuning needed | Synchronous WAL on every credential lookup write is slower than necessary | Acceptable for R1; use `disableWAL(true)` for non-critical writes (e.g., session metadata) after profiling |
| Skip `close()` on RocksDB objects using Spring lifecycle | Relies on Spring shutdown hooks calling `@PreDestroy` | Native memory leaks if Spring context fails to shut down cleanly (e.g., OOM kill) | Never — always call `close()` explicitly in `@PreDestroy` with null checks |

---

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| RocksDB column families | Opening column families one by one in separate `RocksDB.open()` calls | Open all column families in a single `RocksDB.open(options, path, cfDescriptors, cfHandles)` call; separate opens cause WAL management issues |
| RocksDB + Docker | Storing RocksDB data directory inside the container layer (e.g., `/app/data`) | Always mount RocksDB data to a Docker volume (`-v /host/data:/data`); container layer writes bypass OS page cache and are lost on container replace |
| Netty tcnative-boringssl + multi-arch | Declaring a single `netty-tcnative-boringssl-static` dependency without platform classifiers | Declare separate artifacts: `classifier=linux-x86_64` and `classifier=linux-aarch_64`; without this, the ARM64 image silently falls back to JDK SSL (slower, different behavior) |
| Spring Boot Actuator + Netty | Letting Actuator expose its web server on the same Netty EventLoopGroup as MQTT | Give Actuator its own embedded Tomcat/Netty server; if Actuator shares the MQTT event loop, a slow Prometheus scrape can delay MQTT I/O |
| Disruptor + Spring shutdown | Disruptor's `shutdown()` blocks until ring buffer is drained | Register Disruptor shutdown in `@PreDestroy` with a timeout; Spring's default `@PreDestroy` ordering may call other beans before the Disruptor empties, causing `IllegalStateException` |
| MQTT 5.0 clients + MQTT 3.1.1 code paths | Using a single message handler that ignores protocol version | MQTT 5.0 CONNECT, PUBLISH, SUBSCRIBE, DISCONNECT all have additional fields; a handler that only parses 3.1.1 fields silently drops user properties, reason codes, and session expiry — protocol version must gate handler behavior |

---

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Global lock on subscription trie | Publish latency climbs linearly with subscriber count; thread contention visible in heap dumps | Use `ConcurrentHashMap`-backed trie nodes from initial implementation | ~5,000 concurrent subscribers with any wildcard pattern |
| Synchronous RocksDB reads on MQTT connect path | Connect latency spikes under connection storm (e.g., IoT fleet restart) | Use Caffeine to cache recently authenticated credentials; only fall through to RocksDB on cache miss | ~500 simultaneous reconnects per second |
| Netty direct buffer pool sized too small | `OutOfMemoryError: Direct buffer memory` under high message volume | Set `-XX:MaxDirectMemorySize` explicitly; size as `(max connections × message size × pipeline depth)`; default JVM value is typically equal to `-Xmx` | Sustained throughput > 100K msg/sec with large payloads |
| Ring buffer too small for burst load | Disruptor `publishEvent` blocks; I/O threads stall | Size ring buffer as power-of-2 above peak burst capacity: 65536 is a good R1 default for tens of thousands of connections | Single large topic burst with 1,000+ subscribers |
| Per-message object allocation on the hot publish path | GC pauses visible in publish latency percentiles (p99 spikes) | Pre-allocate message events in the Disruptor ring buffer using `EventFactory`; avoid `new MqttPublishMessage()` per message on the I/O thread | Sustained > 50K msg/sec; GC becomes the bottleneck |
| RocksDB compaction stall during peak write load | Credential/ACL write latency spikes for seconds; broker appears to hang | Tune `max_write_buffer_count` and `level0_slowdown_writes_trigger`; use separate column families for hot and cold data | Sustained write-heavy ACL provisioning at broker startup |

---

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Anonymous access enabled by default | Any publicly exposed broker is immediately enumerable and exploitable; documented as widespread in Mosquitto deployments | Default to `ALLOW_ANONYMOUS=false`; require explicit `ALLOW_ANONYMOUS=true` env var for dev mode; log a warning when anonymous access is enabled |
| Credentials stored in RocksDB as plaintext | Credential database compromise exposes all usernames and passwords | Hash passwords with bcrypt (cost factor ≥ 10) before storage; store only the hash, never the plaintext |
| Self-signed cert accepted without verification on mTLS | Man-in-the-middle possible on X.509 auth path | Require explicit CA certificate configuration; reject client certs not signed by the configured CA; do not add a "skip verification" mode — it will be used in production |
| Topic ACL evaluated after message is routed | A subscriber receives a message it should not see if ACL check happens post-delivery | Evaluate ACL during subscription time (SUBSCRIBE response) AND at publish routing time; double-check at routing prevents cached stale permissions from allowing delivery |
| MQTT 5.0 AUTH packet ignored | Enhanced authentication flows silently fall through to plain username/password | MQTT 5.0 AUTH packet must be explicitly handled; if enhanced auth is not supported, return CONNACK with reason code `0x8C` (Bad Authentication Method) — do not silently downgrade |
| TLS cert validation skipped on mounted cert files | Malformed or expired certs cause cryptic runtime failures instead of startup errors | Validate cert files at startup (parse them, check expiry, verify key matches cert); fail fast with a clear error message rather than failing at the first client handshake |

---

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| No persistent sessions in R1 (clean session only) surprises Mosquitto migrants | Users who had `cleansession=0` in their existing setup lose all offline messages on migration; they discover this only after migrating and losing data | Document prominently in README: "R1 is clean-session only. All session state is lost on client disconnect." Provide an upgrade guide to standard TBMQ for persistent session needs |
| Retained messages lost on broker restart with no warning | Users treat retained messages as durable state (device shadow pattern); restart wipes them silently | Log a WARNING on startup: "Retained messages are stored in memory and will be lost on restart. RocksDB persistence for retained messages is planned for R2." |
| RocksDB data directory not mounted as Docker volume | Developer runs `docker run` without `-v`; credentials they create survive until the container is replaced, then vanish | Default the RocksDB path to `/data/rocksdb` and print a WARNING at startup if `/data` appears to be an ephemeral container layer (no volume mount detected) |
| MQTT 1883 plaintext port open by default with no auth prompt | Users forget to configure TLS and ship a plaintext broker to production | Print a WARNING on startup when MQTT plaintext port is open AND TLS is not configured: "WARNING: MQTT plaintext port 1883 is active without TLS. Credentials will be transmitted in cleartext." |
| Feature gap vs standard TBMQ creates confusion | Users see "TBMQ" in the name and expect parity with the enterprise broker (Kafka, PostgreSQL, clustering) | Use a distinct brand name in the Docker image (`tbmq-lightweight`) and a prominent comparison table in the README: "What TBMQ Lightweight is / is not." |

---

## "Looks Done But Isn't" Checklist

- [ ] **QoS 2 handshake:** Often passes basic tests but fails on duplicate detection — verify by sending a PUBLISH with DUP=true and same packet ID while the original PUBREL is in-flight; broker must not deliver twice.
- [ ] **LWT on client takeover:** Often triggers incorrectly — verify by connecting two clients with the same ClientID in rapid succession; LWT from the first client must NOT be published.
- [ ] **WebSocket MQTT (browser):** Often tested only with Paho Java — verify by connecting with `mqttjs` in a real browser and checking `Sec-WebSocket-Protocol: mqtt` is in the handshake response headers.
- [ ] **X.509 mTLS:** Often tested with self-signed certs where both client and broker trust everything — verify rejection of a cert not signed by the configured CA.
- [ ] **Backpressure:** Often not tested — verify that a slow subscriber does not cause unbounded memory growth in the broker by connecting a subscriber that reads at 1 msg/sec while a publisher sends at 1,000 msg/sec.
- [ ] **RocksDB volume persistence:** Often only tested with credentials surviving broker restart in Docker — verify that stopping and restarting the container WITHOUT replacing it preserves credentials, and document what happens when the container IS replaced without a volume mount.
- [ ] **ARM64 Docker image:** Often only tested on CI with QEMU — verify the ARM64 image boots correctly on actual ARM64 hardware (Raspberry Pi 4 or AWS Graviton instance) including RocksDB native library load.
- [ ] **MQTT 5.0 vs 3.1.1 mixed clients:** Often tested with all-5.0 or all-3.1.1 — verify a 3.1.1 client and a 5.0 client can coexist on the same broker and exchange messages through a shared subscription.
- [ ] **Topic ACL for retained messages:** Often ACL is enforced on SUBSCRIBE but not on retained message delivery at subscribe time — verify that a user without READ permission on a topic does not receive the retained message on subscription.

---

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| LWT fires incorrectly on client takeover | MEDIUM | Fix in protocol layer; requires review of all disconnect code paths to add reason classification; regression test suite for reconnect scenarios |
| RocksDB native memory leak (post-v7 `close()` missing) | HIGH | Requires audit of all RocksDB object lifetimes; heap profile with jemalloc to identify allocation sites; fix then validate with 24-hour soak test |
| RocksDB JNI fails on ARM64 Alpine | LOW | Change base image to `eclipse-temurin:17-jre-jammy`; rebuild; no code changes required |
| Netty ByteBuf leak | HIGH | Enable `PARANOID` leak detection; identify stack trace in logs; fix release paths; 24-hour validation with leak detector |
| Disruptor blocks Netty I/O thread | HIGH | Requires architectural change: decouple publish path from I/O thread; cannot be patched without redesigning the dispatch handoff |
| WebSocket subprotocol header missing | LOW | One-line fix in Netty WebSocket handler configuration; verify with browser test |
| Certificate expiry downtime | MEDIUM | Restart broker; implement cert-reload endpoint for post-R1 |
| Anonymous access exposed in production | HIGH | Requires operator to reconfigure and restart; preventable by defaulting to closed; if already compromised, rotate all credentials |

---

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| LWT on client takeover | Core protocol: session + connect/disconnect | Integration test: dual-connect same ClientID, confirm LWT not fired |
| QoS 2 phantom PUBREL | Core protocol: QoS state machine | Protocol compliance test: PUBLISH, disconnect mid-handshake, reconnect, verify no duplicate |
| RocksDB native memory leak | Infrastructure: RocksDB integration | 24-hour soak test monitoring RSS vs heap; confirm stable native memory |
| RocksDB ARM64 Alpine failure | Infrastructure: Docker multi-arch build | ARM64 container starts successfully, RocksDB opens, credentials persist |
| Disruptor blocks Netty I/O | Message dispatch: dispatch layer design | Load spike test: burst 10× normal publish rate, verify I/O threads not BLOCKED |
| Netty ByteBuf leaks | Netty integration: channel pipeline setup | 24-hour load test with `PARANOID` leak detection, zero leak warnings |
| Subscription trie contention | Subscription management | Benchmark: 1,000 concurrent wildcard subscribers, publish latency must not increase with subscriber count |
| Keep-alive false disconnects | Core protocol: keep-alive + IdleStateHandler | Integration test: PINGREQ during sustained publish load, no spurious disconnect |
| WebSocket subprotocol rejection | WebSocket: channel pipeline | Browser-based MQTT client connects successfully; Sec-WebSocket-Protocol header present |
| TLS cert rotation downtime | TLS: certificate handling | Document at R1; cert-reload endpoint as P2 feature |
| Packet ID exhaustion | Message dispatch: QoS 1 ACK path | Benchmark: single client QoS 1 at 10K msg/sec, PUBACK p99 < 1 ms |
| Anonymous access default | Security: authentication layer | Verify broker rejects connection without credentials by default |

---

## Sources

- [RocksDB JNI native memory leak after v7.0.4 (GitHub #9962)](https://github.com/facebook/rocksdb/issues/9962) — confirmed regression, multiple Apache project users affected
- [Slow memory leak with rocksdbjni (GitHub #12020)](https://github.com/facebook/rocksdb/issues/12020) — leak in v8.5.3 JNI, resolved by downgrading to 6.29.5
- [RocksDB Alpine ARM64 failure: libstdc++ not found (GitHub #10651)](https://github.com/facebook/rocksdb/issues/10651) — workaround: `bellsoft/liberica-openjre-alpine-musl` + `apk add libstdc++`
- [No ARM64/aarch64 support for rocksdbjni (GitHub #5559)](https://github.com/facebook/rocksdb/issues/5559) — historical issue; resolved since v6.29.x for glibc
- [TBMQ backpressure documentation — Netty watermark config](https://thingsboard.io/docs/mqtt-broker/user-guide/backpressure/) — HIGH confidence (own product docs)
- [Netty write buffer watermarks and backpressure (GitHub #10254)](https://github.com/netty/netty/issues/10254) — write throttling design discussion
- [reactor-netty ByteBuf memory leak (GitHub #3274)](https://github.com/reactor/reactor-netty/issues/3274) — confirmed leak in Netty 4.1.100.Final; pattern applies broadly
- [A Netty ByteBuf Memory Leak Story (Logz.io)](https://logz.io/blog/netty-bytebuf-memory-leak/) — production postmortem with detection methodology
- [HiveMQ MQTT Topic Tree Matching Challenges](https://www.hivemq.com/blog/mqtt-topic-tree-matching-challenges-best-practices-explained/) — authoritative analysis of subscription trie performance
- [LMAX Disruptor: ring buffer full and producer blocking (Google Groups)](https://groups.google.com/g/lmax-disruptor/c/8laL6xd7ag4) — confirmed blocking behavior when ring is full
- [Disruptor ring buffer performance bottlenecks (JavaNexus)](https://javanexus.com/blog/disruptors-ring-buffer-performance-bottlenecks) — producer-consumer mismatch failure modes
- [EMQX QoS 2 DUP reconnect bug (GitHub #14688)](https://github.com/emqx/emqx/issues/14688) — February 2025; real-world QoS 2 state management failure
- [LWT unclear behavior with client takeover (Google Groups)](https://groups.google.com/g/mqtt/c/cdFEHZXRvuY) — spec ambiguity discussion; MQTT 5.0 clarification
- [Mosquitto LWT + client takeover (GitHub #904)](https://github.com/eclipse/mosquitto/issues/904) — confirmed bug in Mosquitto implementation
- [EMQX keep-alive backoff discussion (GitHub #14308)](https://github.com/emqx/emqx/discussions/14308) — real-world keep-alive timing deviation; 1.5x vs 2.25x actual disconnect
- [MQTT over WebSocket subprotocol header (mqttjs GitHub #408)](https://github.com/mqttjs/MQTT.js/issues/408) — browser client failure due to missing subprotocol response
- [WebSocket subprotocol response ASP.NET Core (GitHub #2468)](https://github.com/dotnet/aspnetcore/issues/2468) — "Sent non-empty Sec-WebSocket-Protocol header" error pattern
- [RocksDB Setup Options and Basic Tuning (official wiki)](https://github.com/facebook/rocksdb/wiki/Setup-Options-and-Basic-Tuning) — column family WAL config, block cache setup
- [RocksDB Column Families — WAL and flush relationship (official wiki)](https://github.com/facebook/rocksdb/wiki/Column-Families) — multi-column-family WAL stall pitfall
- [Netty leakDetection levels (official docs)](https://netty.io/wiki/using-as-a-generic-library.html) — PARANOID/SIMPLE/DISABLED guidance
- [Your MQTT broker might be public (HowToGeek)](https://www.howtogeek.com/your-mqtt-broker-might-be-public/) — anonymous access default security risk, documented in the wild
- [MQTT 5.0 specification — Will Message delivery conditions (OASIS)](https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html) — Section 3.1.3.2, Will Delay Interval behavior on session takeover

---
*Pitfalls research for: lightweight embedded MQTT broker (TBMQ Lightweight)*
*Researched: 2026-04-02*
