# Feature Research

**Domain:** Lightweight MQTT broker (single-node, zero-dependency, developer-first)
**Researched:** 2026-04-02
**Confidence:** MEDIUM-HIGH (verified against official docs, GitHub, and multiple independent sources)

---

## Competitive Landscape Summary

| Broker | Language | Protocol | Key Constraint | Connection Limit |
|--------|----------|----------|----------------|-----------------|
| Mosquitto | C | 3.1/3.1.1/5.0 | Single-threaded, no clustering | ~100k practical ceiling |
| EMQX (BSL, v5.9+) | Erlang | 3.1/3.1.1/5.0 + QUIC | Single-node free; cluster requires license | Unlimited (single node) |
| HiveMQ CE | Java | 3.1/3.1.1/5.0 | No clustering in CE; no Control Center | Unlimited (CE) |
| NanoMQ | C | 3.1/3.1.1/5.0 | No MQTT 5.0 Auth/Redirect; edge focus only | Resource-bound |

**EMQX licensing note (HIGH confidence):** EMQX moved to BSL 1.1 in v5.9.0 (May 2025). Single-node production deployments remain free with full feature access; clustering requires a commercial license. This is a meaningful competitive shift — EMQX's free tier is now functionally a single-node broker like TBMQ Lightweight.

**HiveMQ CE note (MEDIUM confidence):** The 25-connection limit applies to the enterprise trial license, NOT to Community Edition. HiveMQ CE is Apache 2.0, unlimited connections, but lacks clustering and the commercial Control Center.

---

## Feature Landscape

### Table Stakes (Users Expect These)

Features users assume any MQTT broker ships with. Absence triggers immediate rejection or a support ticket — users do not give credit for these.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| MQTT 3.1.1 full compliance | Universal device firmware default; 99% of IoT SDKs default to 3.1.1 | LOW | All four competitors ship this |
| MQTT 5.0 full compliance | Professional/modern tooling assumes 5.0; 3.1.1-only feels outdated | MEDIUM | All four competitors ship this; NanoMQ skips Auth + Server Redirect packets |
| QoS 0, 1, 2 flows | Protocol contract — QoS 1/2 used heavily in industrial/reliability-sensitive contexts | MEDIUM | QoS 2 handshake (PUBREC/PUBREL/PUBCOMP) must be correct or triggers inter-op bugs |
| TLS/SSL (server cert) | Security baseline; plaintext MQTT on internet-facing brokers is called out publicly | LOW | Mount cert files; standard JVM keystore/truststore pattern |
| Username/password authentication | Every tutorial and quickstart guide uses this; absent = broker feels unsafe | LOW | File-backed or DB-backed; Mosquitto uses flat file, EMQX/HiveMQ use pluggable auth |
| Retained messages | Core MQTT feature used heavily for "last known state" patterns (e.g. device shadow) | LOW | In-memory for R1 is acceptable; users will ask about persistence across restart |
| Last Will and Testament (LWT) | Fundamental device disconnection signaling; "is my device alive?" pattern | LOW | Standard CONNECT packet handling; no extra storage needed |
| MQTT over WebSocket | Browser-based dashboards and web-app clients require WS; wss:// for secure | LOW | Both ws:// and wss:// expected; port 9001 is community convention |
| Topic-based ACL (publish/subscribe) | Security expectation — users don't want all clients reading all topics | MEDIUM | Mosquitto uses flat ACL files; EMQX/HiveMQ use richer rule sets; RocksDB backend is fine |
| Docker single-command startup | In 2025, zero-dependency `docker run` is the standard evaluation path; multi-step setup = abandoned evaluation | LOW | `docker run -p 1883:1883 image:tag` must work without config files for defaults |
| Basic observability metrics | Operators need connection counts, message rates, error rates; "how is my broker doing?" | MEDIUM | Mosquitto lacks native Prometheus; EMQX and HiveMQ CE expose metrics; Prometheus /metrics endpoint is the 2025 standard |

### Differentiators (Competitive Advantage)

Features that elevate TBMQ Lightweight above the commodity tier. Not required to ship, but valued where present. Align with the "built on enterprise engine" value proposition.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| X.509 certificate-based client auth | Strong identity without shared secrets; required in industrial and automotive IoT | MEDIUM | Mutual TLS during handshake; all four competitors support it but Mosquitto's config is notoriously painful |
| MQTT 5.0 session expiry interval | Clean Start behavior with expiry gives clients graceful reconnect without full re-subscribe overhead | LOW | Part of MQTT 5.0 spec; properly honoring session expiry interval signals protocol completeness |
| MQTT 5.0 user properties | Custom metadata per message; enables routing, tracing, multi-tenant tagging without topic hacks | LOW | Zero broker logic beyond forwarding; purely protocol compliance, but missing it causes friction for advanced clients |
| MQTT 5.0 shared subscriptions | Load-balancing across subscriber groups; heavily used in backend consumer patterns | MEDIUM | Mosquitto lacks this; EMQX/HiveMQ support it; important for users building competing-consumer architectures |
| Prometheus /metrics endpoint (native) | Standard DevOps toolchain integration; works with every modern monitoring stack without extra exporters | MEDIUM | Mosquitto requires a third-party exporter (Cedalo Pro or community tools); native endpoint is a clear differentiator vs Mosquitto |
| Multi-arch Docker image (x86 + ARM) | Edge deployments run ARM (Raspberry Pi, Jetson, industrial gateways); ARM-only operators otherwise cannot evaluate | MEDIUM | NanoMQ explicitly targets this; TBMQ Lightweight should match; Docker buildx multi-platform build |
| Zero-config secure default (credentials required for non-localhost) | Mosquitto's default is open to all IPs — actively exploited in the wild; "your MQTT broker is public" is a documented security problem | LOW | Block anonymous access by default or warn loudly; significant differentiator in security posture |
| Migration path to enterprise TBMQ | Same engine under the hood; configuration and client code survives the upgrade; no broker re-evaluation needed | LOW (framing, not engineering) | This is a product/marketing differentiator, not a feature to build — but must be documented and surfaced |
| Structured startup logs / clear error messages | Developers spend hours debugging Mosquitto TLS configuration failures with cryptic errors; clear logs reduce time-to-first-connection | LOW | Primarily an implementation quality concern, not a feature per se — but measurably differentiating |

### Anti-Features (Commonly Requested, Often Problematic)

Features that sound good but would undermine the R1 goal or create long-term maintenance debt.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Web UI / management dashboard | Users want a visual view of connections, topics, ACLs | Adds frontend build pipeline, separate service, auth surface; contradicts zero-dependency value; deferred to future release for good reason | Expose REST API + Prometheus metrics; users can point any Grafana instance at /metrics |
| Persistent sessions across restarts | Users want offline message delivery; feels like a missing feature | Requires durable session store, message queuing, per-client offset tracking — substantially more than an in-memory broker; this is an R2 feature | Document the limitation clearly in README; provide upgrade path to standard TBMQ |
| Clustering / HA | "What if the node goes down?" | Breaks the single-Docker-image contract; clustering is the enterprise product's value proposition | Document scale ceiling; for HA needs, point to standard TBMQ |
| Plugin/extension system (HiveMQ Extension SDK style) | Extensibility sounds good | Adds API surface to maintain; draws attention away from protocol correctness; premature for R1 | Use configuration-driven auth backends (username/pass + X.509 covers 95% of cases) |
| Rule engine / data transformation | Advanced users want to filter/route messages via SQL rules | Rule engines are a product in themselves (EMQX's rule engine is a major differentiator in their enterprise pitch); scope creep risk | Stay focused on the broker; integrations belong in the application layer or a future enterprise TBMQ feature |
| Rate limiting per client | Operators want to protect broker from noisy clients | Complex to implement correctly (token bucket per client ID, with edge cases around reconnects); deferred for good reason in R1 | QoS 2 flow control provides natural back-pressure; document the limitation |
| MQTT-SN / CoAP / LwM2M gateways | Some IoT hardware speaks only MQTT-SN or CoAP | Protocol gateway complexity is disproportionate to R1 user base; NanoMQ specializes here | Out of scope; standard MQTT devices are the target |
| Anonymous access by default | "Just works" for demos | Publicly exposed unsecured brokers are a documented attack surface; home assistant users regularly get their smart home compromised | Default to requiring credentials; provide an explicit `ALLOW_ANONYMOUS=true` env var for dev mode |

---

## Feature Dependencies

```
[MQTT 3.1.1 compliance]
    └──required by──> [QoS 0/1/2 flows]
    └──required by──> [Retained messages]
    └──required by──> [LWT]
    └──required by──> [Username/password auth]
    └──required by──> [Topic ACL]

[MQTT 5.0 compliance]
    └──required by──> [Session expiry interval]
    └──required by──> [User properties]
    └──required by──> [Shared subscriptions]
    └──requires──> [MQTT 3.1.1 compliance] (backwards compat negotiation)

[TLS/SSL]
    └──required by──> [X.509 certificate auth] (mTLS handshake requires TLS layer)
    └──required by──> [MQTT over WSS (wss://)]
    └──enhances──> [Username/password auth] (credentials over plaintext = bad)

[MQTT over WebSocket]
    └──required by──> [wss:// WebSocket] (TLS layer on top of WS)

[Username/password auth]
    └──required by──> [Topic ACL] (ACL entries reference username/client ID)

[X.509 cert auth]
    └──enhances──> [Topic ACL] (cert CN can serve as identity in ACL rules)

[Prometheus /metrics endpoint]
    ──independent──> [All other features] (observability layer, no deps)

[Docker single-command startup]
    ──requires──> [All core features working with defaults] (zero config means defaults must be sensible)
```

### Dependency Notes

- **X.509 cert auth requires TLS:** Mutual TLS is the transport for certificate exchange. You cannot have mTLS without TLS being operational first. Build and validate TLS before building X.509 auth.
- **Topic ACL requires auth:** ACL rules reference an identity (username or cert CN). ACL without auth is meaningless. Auth must ship in same phase as ACL.
- **MQTT 5.0 requires 3.1.1:** Brokers negotiate protocol version during CONNECT. 5.0 code path must gracefully fall back to 3.1.1 for older clients. 3.1.1 must be complete before 5.0 is layered on.
- **WSS requires both WebSocket and TLS:** wss:// is TLS tunneled over WebSocket. Both layers must be independently validated before combining.
- **Shared subscriptions conflict with clean-session-only R1:** Shared subscriptions in MQTT 5.0 work with clean sessions, but the full benefit emerges with persistent consumer groups. Implement in R1 as best-effort (clean session shared subs work) with R2 persistent semantics.

---

## MVP Definition

### Launch With (R1 — TBMQ Lightweight v1)

Minimum viable product — what is needed to be a credible, usable MQTT broker.

- [ ] MQTT 3.1.1 full compliance (CONNECT/PUBLISH/SUBSCRIBE/UNSUBSCRIBE/PING/DISCONNECT) — protocol correctness is non-negotiable
- [ ] MQTT 5.0 full compliance including session expiry, user properties, shared subscriptions — modern tooling default
- [ ] QoS 0/1/2 flows — QoS 2 is hardest to get right; ship it or explicitly document absence
- [ ] Retained messages (in-memory) — core "last known value" pattern; absence is noticeable
- [ ] LWT — device disconnection signaling; every IoT tutorial uses it
- [ ] TLS (server-side, mounted certs) — security baseline for internet-facing deployments
- [ ] MQTT over WebSocket (ws:// and wss://) — browser clients and IoT dashboards require this
- [ ] Username/password authentication backed by RocksDB — survive restarts; flat files are too fragile
- [ ] X.509 certificate-based client authentication — industrial/automotive use cases; differentiator vs Mosquitto
- [ ] Topic-level ACL stored in RocksDB — authorization that survives restarts
- [ ] Prometheus /metrics endpoint — native observability; no external exporter required
- [ ] Single Docker image, zero config for defaults, `docker run -p 1883:1883 image:tag` works — evaluation path must be one command

### Add After Validation (R2)

Features to add once core is working and user feedback is received.

- [ ] Persistent sessions / offline message queuing — the most requested deferred feature; add when durable storage layer is proven
- [ ] Durable retained messages (survive restarts) — currently in-memory only; RocksDB can back this in R2
- [ ] REST management API — before a Web UI, an API unblocks programmatic management (user creation, ACL management)
- [ ] Rate limiting per client connection — needed for multi-tenant or public-facing brokers
- [ ] Web UI — management console; deferred to reduce R1 scope but a clear R2/R3 candidate

### Future Consideration (v2+ / Enterprise Migration Point)

Features to defer until product-market fit is established or until routing to standard TBMQ makes more sense.

- [ ] Clustering / horizontal scaling — this is the standard TBMQ value proposition; adding it to Lightweight creates product confusion
- [ ] Rule engine / data transformation — belongs in enterprise tier
- [ ] ThingsBoard PE/CE integration — deferred; enables value-chain story but complex
- [ ] Plugin/extension SDK — premature; adds API surface before protocol surface is proven stable

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| MQTT 3.1.1 full compliance | HIGH | LOW (existing engine) | P1 |
| MQTT 5.0 full compliance | HIGH | MEDIUM | P1 |
| QoS 0/1/2 | HIGH | MEDIUM (QoS 2 state machine) | P1 |
| TLS (server cert) | HIGH | LOW | P1 |
| Username/password auth | HIGH | LOW | P1 |
| Retained messages (in-memory) | HIGH | LOW | P1 |
| LWT | HIGH | LOW | P1 |
| MQTT over WebSocket | HIGH | LOW | P1 |
| Topic ACL | HIGH | MEDIUM | P1 |
| Docker single-image startup | HIGH | LOW | P1 |
| Prometheus /metrics | MEDIUM | MEDIUM | P1 |
| X.509 cert auth | MEDIUM | MEDIUM | P1 |
| MQTT 5.0 shared subscriptions | MEDIUM | MEDIUM | P2 |
| Multi-arch Docker (ARM + x86) | MEDIUM | LOW | P2 |
| REST management API | MEDIUM | HIGH | P2 |
| Persistent sessions | HIGH | HIGH | P2 (R2) |
| Rate limiting | MEDIUM | HIGH | P3 |
| Web UI | MEDIUM | HIGH | P3 |
| Rule engine | LOW | HIGH | P3 |

**Priority key:**
- P1: Must have for R1 launch
- P2: Should have, add in R2 or during R1 if time allows
- P3: Nice to have, future consideration

---

## Competitor Feature Analysis

| Feature | Mosquitto | EMQX (BSL, single node) | HiveMQ CE | NanoMQ | TBMQ Lightweight R1 |
|---------|-----------|------------------------|-----------|--------|---------------------|
| MQTT 3.1.1 | Yes | Yes | Yes | Yes | Yes |
| MQTT 5.0 | Yes | Yes | Yes | Partial (no Auth/Redirect) | Yes |
| QoS 0/1/2 | Yes | Yes | Yes | Yes | Yes |
| Retained messages | Yes (file) | Yes | Yes | Yes | Yes (in-memory) |
| LWT | Yes | Yes | Yes | Yes | Yes |
| TLS | Yes | Yes | Yes | Yes | Yes |
| WebSocket | Yes | Yes | Yes | Yes | Yes |
| Username/password auth | Yes (flat file) | Yes (pluggable) | Yes (extensible) | Yes | Yes (RocksDB) |
| X.509 cert auth | Yes (config-heavy) | Yes | Yes (via extension) | Yes | Yes |
| Topic ACL | Yes (flat file) | Yes (rich rules) | Yes (via extension) | Yes | Yes (RocksDB) |
| Shared subscriptions | No | Yes | Yes | Yes | Yes (MQTT 5.0) |
| Prometheus metrics | No (third-party only) | Yes (native) | No (enterprise only) | No | Yes (native) |
| Clustering | No | License required | No (CE) | No | No (by design) |
| Persistent sessions | Yes | Yes | Yes | Yes | No (R1) |
| Web UI | No | Yes | No (CE) | No | No (R1) |
| Single Docker image | Yes | Yes | Yes | Yes | Yes |
| Multi-arch (ARM) | Yes | Yes | Yes | Yes | Planned |
| License | EPL/EDL | BSL 1.1 | Apache 2.0 | MIT | TBD |

### Observations

**Mosquitto gap TBMQ Lightweight fills:** Native Prometheus metrics, credentials backed by proper storage (RocksDB vs flat file), cleaner defaults (no anonymous by default).

**EMQX gap TBMQ Lightweight fills:** EMQX 5.9+ is BSL — not open source. For teams that require Apache 2.0 or a ThingsBoard-ecosystem broker, EMQX is no longer the free alternative it was. TBMQ Lightweight can position on license transparency and Java ecosystem familiarity.

**HiveMQ CE gap TBMQ Lightweight fills:** HiveMQ CE has no native metrics and requires the Java Extension SDK for auth/ACL customization. TBMQ Lightweight ships auth and ACL out of the box without extension development.

**NanoMQ gap TBMQ Lightweight fills:** NanoMQ is C-based, targets sub-200KB footprint, and is designed for embedded systems. TBMQ Lightweight targets developers and small production deployments — a broader audience that values JVM predictability and a clear migration path to enterprise TBMQ.

---

## Common User Complaints by Broker (Research-backed)

### Mosquitto
- No web interface; monitoring requires external tools or manual log parsing
- Flat-file ACL and auth configuration does not scale; changes require restart
- No clustering; bridging is manual and error-prone, not a real cluster
- Default anonymous access leaves brokers publicly exposed — documented security problem (HowToGeek, 2025)
- Single-threaded architecture; performance degrades under high publish rates
- File-based persistence does not scale; single-file store corrupts under load

### EMQX (Community, pre-5.9)
- Resource-heavy for small deployments; overkill for single-node eval
- BSL license change (5.9+) broke automated workflows that relied on Apache 2.0 Docker image
- QoS 2 bridge interop issues with Mosquitto (PUBREL duplication bug, Oct 2024)
- Dashboard requires login; port 18083 surprises developers expecting just 1883

### HiveMQ CE
- No built-in metrics; Control Center is enterprise-only
- Authentication and ACL require developing a Java extension (Extension SDK) — high barrier for simple deployments
- No clustering in CE; the headline feature of HiveMQ is paywalled
- JVM startup time higher than C-based brokers for lightweight scenarios

### NanoMQ
- Missing MQTT 5.0 Auth and Server Redirect packets — subtle compliance gaps cause test suite failures
- Documentation quality inconsistent; primarily maintained by NNG/EMQ team
- Edge-only positioning; not suitable for developer-laptop evaluation workflows
- No persistent storage abstractions; limited production deployment guidance

---

## Sources

- [Comparison of Open Source MQTT Brokers 2025 | EMQ](https://www.emqx.com/en/blog/a-comprehensive-comparison-of-open-source-mqtt-brokers-in-2023) — MEDIUM confidence (EMQ is a vendor; competitive claims bias possible)
- [Mosquitto MQTT Broker: Pros/Cons, Tutorial, and Modern Alternatives | EMQ](https://www.emqx.com/en/blog/mosquitto-mqtt-broker-pros-cons-tutorial-and-modern-alternatives) — MEDIUM confidence (same vendor caveat)
- [HiveMQ vs. Mosquitto: An MQTT Broker Comparison | HiveMQ](https://www.hivemq.com/blog/hivemq-vs-mosquitto-an-mqtt-broker-comparison/) — MEDIUM confidence (HiveMQ vendor; enterprise bias)
- [EMQX Open Source vs. Enterprise | EMQ](https://www.emqx.com/en/blog/emqx-open-source-vs-enterprise) — HIGH confidence (official feature list from vendor)
- [EMQX BSL License Announcement | EMQ](https://www.emqx.com/en/blog/adopting-business-source-license-to-accelerate-mqtt-and-ai-innovation) — HIGH confidence (official announcement)
- [EMQX BSL Discussion #15163 | GitHub](https://github.com/emqx/emqx/discussions/15163) — HIGH confidence (official maintainer response)
- [HiveMQ Community Edition | GitHub](https://github.com/hivemq/hivemq-community-edition) — HIGH confidence (official source)
- [HiveMQ CE FAQ | HiveMQ Community Forum](https://community.hivemq.com/t/frequently-asked-questions-about-hivemq-ce/2043) — HIGH confidence (official maintainer responses)
- [NanoMQ | GitHub](https://github.com/nanomq/nanomq) — HIGH confidence (official source)
- [Your MQTT broker might be public | HowToGeek](https://www.howtogeek.com/your-mqtt-broker-might-be-public/) — HIGH confidence (independent editorial)
- [MQTT 5.0 specification | OASIS Open](https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html) — HIGH confidence (standards body)
- [X.509 Certificate Chain Authentication | ThingsBoard Docs](https://thingsboard.io/docs/mqtt-broker/security/authentication/x509/) — HIGH confidence (own product docs)
- [Monitoring MQTT Broker for KPIs | HiveMQ Blog](https://www.hivemq.com/blog/monitoring-mqtt-broker-for-kpis/) — MEDIUM confidence
- [Mosquitto to Prometheus export | Cedalo](https://cedalo.com/blog/mqtt-to-prometheus/) — MEDIUM confidence (Cedalo is commercial Mosquitto vendor)

---
*Feature research for: lightweight MQTT broker (TBMQ Lightweight)*
*Researched: 2026-04-02*
