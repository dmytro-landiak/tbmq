# TBMQ Lightweight
Zero-dependency MQTT brokering in a single container.

## What it is

TBMQ Lightweight is a stripped-down, single-node variant of TBMQ that eliminates all external infrastructure dependencies — no Kafka, no PostgreSQL, no Redis/Valkey. It ships as a single Docker image and is designed for easy evaluation, small-scale production deployments, and environments where operational simplicity matters more than horizontal scalability. It covers the core MQTT protocol surface with an embedded storage and messaging layer, trading cluster capabilities and some persistence guarantees for dramatically lower deployment friction.

Whether this ships as a separate repository or as a configurable mode inside the existing TBMQ codebase is an open decision — the preferred direction is a **separate repository** to keep the standard TBMQ codebase clean and let the lightweight variant evolve independently.

---

## The problem

TBMQ in its current form is a production-grade, horizontally scalable MQTT broker — and it requires the infrastructure to match: Kafka for inter-node messaging, PostgreSQL for persistent state, and Redis/Valkey for distributed coordination. This is the right architecture for large deployments, but it creates a high barrier to entry for:

- Developers evaluating TBMQ who need to spin up a working broker in minutes, not hours
- Small-scale production deployments (edge nodes, single-site installations, embedded systems) where running Kafka + PSQL + Redis alongside a broker is operationally unreasonable
- Customers exploring migration from lightweight brokers like EMQX Community or Mosquitto, for whom a multi-service deployment is a hard blocker

As shown by comparison, starting EMQX is a single `docker run` command. Starting TBMQ today requires a multi-step install script that provisions multiple services. For customers in the evaluation or early adoption phase, this gap directly affects conversion.

---

## The solution

TBMQ Lightweight replaces all external runtime dependencies with embedded alternatives:

| Dependency | Standard TBMQ | Lightweight |
|---|---|---|
| Message queue | Kafka | In-process queue (e.g. Disruptor / blocking queue) |
| Relational persistence | PostgreSQL | RocksDB (embedded key-value store) |
| Distributed cache / coordination | Redis / Valkey | In-process cache (Caffeine or similar) |
| Deployment | Multi-container (docker-compose / K8s) | Single Docker image |
| Clustering | Supported | Not supported (single node only) |

The result is a broker that starts with `docker run` and requires no external configuration for basic use.

---

## Key features

### Release 1 — Core

- **Full MQTT 3.1.1 and MQTT 5.0 protocol support** — CONNECT, PUBLISH, SUBSCRIBE, UNSUBSCRIBE, PING, DISCONNECT with correct QoS 0, 1, 2 flows
- **In-memory session state** — active client sessions are tracked in memory for the lifetime of the broker process; sessions are not persisted across restarts
- **Non-persistent messaging** — messages are delivered to currently connected subscribers; no offline message queuing for disconnected clients (Clean Session / Clean Start semantics only in R1)
- **Retained messages** — stored in memory for the lifetime of the broker process; persistence across restarts is deferred to a future release
- **Last Will and Testament (LWT)** — full support
- **TLS termination** — configurable via mounted certificates; no external termination required
- **MQTT over WebSocket** — `ws://` and `wss://` listener support
- **Authentication** — username/password and X.509 certificate-based client auth, backed by RocksDB
- **Authorization** — topic-level ACL rules, stored in RocksDB
- **Basic metrics endpoint** — Prometheus-compatible `/metrics` for connection counts, message rates, and error counters
- **Single Docker image** — `docker run -p 1883:1883 thingsboard/tbmq-lightweight` is a fully working broker

### Out of scope for Release 1

- **Persistent sessions** — QoS 1/2 message queuing for offline/disconnected clients
- **Durable subscriptions** — retained subscription state across broker restarts
- **Clustering / horizontal scaling** — single node only; scale-out requires standard TBMQ
- **ThingsBoard PE/CE integration** — TBMQ integration connector to TB is deferred; lightweight version is a standalone broker in R1
- **MQTT over WebSocket over TLS (WSS) load balancing** — out of scope without clustering

### Future releases

- **Persistent sessions and offline message queuing** — store QoS 1/2 messages for disconnected clients in RocksDB, respecting per-client queue limits
- **Durable subscription persistence** — survive broker restarts with full session state restored from RocksDB
- **ThingsBoard integration** — publish MQTT telemetry directly to a connected TB instance (rule engine / device telemetry pipeline)
- **Web UI** — the existing TBMQ web UI ships as part of the lightweight image; all UI functionality that corresponds to a backend feature present in Release 1 is included; sections backed by unavailable features (clustering, persistent sessions, etc.) are deferred accordingly
- **Rate limiting** — per-client and per-topic publish rate limiting via in-process state


---

## How it works under the hood

Without Kafka, the broker routes messages between publisher and subscriber sessions using an in-process dispatch layer. Candidate implementations include a Disruptor-based ring buffer or a standard Java `BlockingQueue` — to be evaluated for throughput and latency characteristics at the target scale (tens of thousands of concurrent connections on a single node).

Without PostgreSQL, broker state that must survive restarts (credentials, ACLs) is written to **RocksDB** via an embedded JNI binding. RocksDB is already used in the broader ThingsBoard ecosystem (TB PE uses it in specific queue configurations), is battle-tested for embedded key-value workloads, and has a small on-disk footprint. Session state that does not need to survive restarts (active subscriptions, in-flight QoS state for connected clients) lives purely in-memory.

Without Redis/Valkey, coordination primitives that in the clustered version rely on distributed cache (duplicate message detection, session locking) are replaced with in-process equivalents — acceptable because single-node deployment removes the need for distributed coordination entirely.

The Docker image bundles the broker JVM process and an embedded RocksDB data directory. Persistent data (retained messages, credentials) is written to a volume-mountable path so that broker restarts do not lose configuration.

---

## Repository strategy

Two approaches exist for delivering TBMQ Lightweight:

**Option A — Separate repository (preferred)**
A new repository (`tbmq-lightweight` or similar) contains the lightweight broker as a standalone project. It may share library-level modules with the main TBMQ repo (protocol parsing, MQTT state machines) via published artifacts, but has its own infrastructure layer, build pipeline, and release cycle. This keeps the standard TBMQ codebase free of lightweight-mode branching logic, allows the lightweight variant to have a faster release cadence, and makes the deployment and contribution story simpler for each audience.

**Option B — Configurable mode in the existing repo**
A `TBMQ_MODE=lightweight` environment variable switches the broker's infrastructure backend at startup — swapping Kafka adapters for in-process queues, PSQL repositories for RocksDB-backed ones, and so on. This avoids code duplication for shared protocol logic but introduces persistent complexity in the main codebase (feature flags, dual infrastructure paths, integration test matrix explosion).

**Decision: Option A is preferred.** The infrastructure layers are different enough that maintaining them in the same repo creates more long-term cost than it saves. Shared protocol logic can be extracted to a common library if duplication becomes a concern.

---

## What makes it different

Unlike **Mosquitto**, TBMQ Lightweight is built on the same MQTT engine as the enterprise-grade TBMQ, making migration from lightweight to clustered deployment a configuration change rather than a platform switch. Unlike **EMQX Community**, it is fully open-source with no feature gating and is designed to integrate natively with the ThingsBoard ecosystem. Unlike standard **TBMQ**, it requires zero external infrastructure — no Kafka, no Postgres, no Redis — making it viable for edge deployments and evaluation environments where running a full data platform stack is not an option.

---

## Competitive landscape

| Broker | External deps | Cluster support | TB integration | Notes |
|---|---|---|---|---|
| Mosquitto | None | No | No | Industry standard for lightweight; no enterprise features |
| EMQX Community | None (built-in Mnesia) | Yes | No | Strong feature set; community edition has limitations |
| HiveMQ Community | None | No | No | JVM-based; limited to 25 connections in community edition |
| NanoMQ | None | No | No | C-based, ultra-lightweight, embedded/IoT focus |
| Standard TBMQ | Kafka + PSQL + Redis | Yes | Yes | Full-featured; high deployment cost |
| **TBMQ Lightweight** | **None** | **No** | **R2+** | **TBMQ core, single image, upgrade path to full TBMQ** |

---

## Target users

- **Developers and solution architects** evaluating TBMQ before committing to a full deployment
- **Integrators** building single-site or edge deployments where a Kafka/Postgres/Redis stack is impractical
- **ThingsBoard customers** who want MQTT brokering tightly aligned with TB but don't yet need multi-node broker clustering
- **Existing lightweight broker users** (Mosquitto, HiveMQ Community) considering migration to a more capable broker without the infrastructure overhead of standard TBMQ

---

## First run experience

**Integrator setup:**
```bash
docker run -d --name tbmq \
  -p 1883:1883 -p 8083:8083 -p 8084:8084 \
  -v tbmq-data:/data \
  thingsboard/tbmq-lightweight
```
The broker is ready to accept connections within seconds. No external services, no install scripts, no configuration required for basic use.

**Developer validation:**
Connect any MQTT client to `localhost:1883`. Publish and subscribe immediately. Optionally mount a config file for TLS, custom credentials, or ACL rules.

---

## Risks and mitigations

### Technical risks

| Risk | Impact | Mitigation |
|---|---|---|
| In-process queue becomes a bottleneck at high message rates on a single node | Throughput ceiling lower than expected | Benchmark candidate queue implementations (Disruptor vs. BlockingQueue vs. LMAX) early; document expected throughput limits clearly |
| RocksDB JNI adds JVM startup complexity and platform-specific native library packaging | Increased Docker image size; potential native library issues on ARM/x86 | Validate multi-arch Docker builds early; evaluate alternative embedded stores (H2, MapDB, Chronicle Map) as fallbacks if RocksDB JNI proves problematic |
| Feature gap vs. standard TBMQ creates confusion about which version to use | Wrong version selected for production; support burden | Clear documentation on the decision matrix (single node / no persistence needs → lightweight; multi-node / offline clients → standard TBMQ) |
| No persistent sessions in R1 surprises users coming from Mosquitto | Negative feedback; perceived feature regression | Make the limitation explicit in docs and the README; provide a clear roadmap for R2 persistent session support |

### Product risks

| Risk | Impact | Mitigation |
|---|---|---|
| Maintenance burden of two codebases | Engineering overhead | Extract shared MQTT protocol logic into a common internal library early; keep the scope of the lightweight repo narrow |
| Lightweight version cannibalizes standard TBMQ adoption | Revenue / adoption impact | Lightweight is intentionally single-node with no clustering; users who need scale will always need standard TBMQ. The upgrade path should be clearly documented and smooth. |
| Scope creep toward replicating standard TBMQ features | Delays release, defeats the purpose | R1 scope is fixed: core MQTT, no offline queuing, no clustering, no TB integration. All additions go to a versioned roadmap. |

---

## Open decisions

- **Repository name and org structure** — `tbmq-lightweight`, `tbmq-lite`, or `tbmq-standalone`? Separate GitHub org or under `thingsboard/`?
- **Embedded storage selection** — RocksDB is the leading candidate, but H2 (pure Java, no JNI) and Chronicle Map (off-heap, fast) are worth evaluating for operational simplicity vs. performance trade-offs
- **In-process queue implementation** — Disruptor (high throughput, low latency) vs. standard Java concurrency primitives (simpler, lower risk); to be decided by benchmarking
- **R1 feature boundary for QoS** — should QoS 1/2 delivery to *currently connected* clients be supported in R1 (in-memory in-flight tracking only), or deferred entirely to R2 alongside persistent session support?
- **Metrics and observability** — Prometheus `/metrics` endpoint in R1, or defer to R2?
- **Default data directory** — volume mount path convention and default embedded RocksDB location
