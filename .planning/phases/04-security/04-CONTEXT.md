# Phase 4: Security - Context

**Gathered:** 2026-04-08
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase adds authentication, authorization, and TLS to the broker. Clients must authenticate before connecting (username/password or X.509 certificate), topic-level ACL rules are enforced on publish and subscribe, TLS terminates at the broker via mounted PEM certificates, and mutual TLS is supported for client certificate authentication. All credentials and ACL rules are stored in RocksDB so they persist across broker restarts. Anonymous access is disabled by default but configurable via environment variable for development use.

</domain>

<decisions>
## Implementation Decisions

### Credential Storage (AUTH-01, AUTH-05)
- **D-01:** Credentials stored in RocksDB `CREDENTIALS` column family (already defined in Phase 1). Key: credential identifier (username for basic auth, CN/fingerprint for X.509). Value: JSON-serialized credential object (Jackson). JSON chosen for debuggability per Phase 1 decision D-05.
- **D-02:** Two credential types: `BASIC` (username + bcrypt-hashed password) and `SSL` (X.509 certificate CN or SHA-256 fingerprint). Copy TBMQ's `MqttClientCredentials` model and `BasicCredentials`/`SslCredentials` patterns, trimming PostgreSQL/JPA dependencies.
- **D-03:** Default admin credentials pre-loaded on first start (install profile) so the broker is immediately usable after `docker run`. Username: `tbmq`, password: `tbmq` (matching TBMQ convention).

### Authentication Pipeline (AUTH-01, AUTH-02, AUTH-04)
- **D-04:** Authentication happens at actor level during CONNECT processing in ClientActor, matching TBMQ's pattern. On MQTT CONNECT, ClientActor extracts credentials from the connect message (username/password fields or SSL peer certificate from the channel's SslHandler) and validates against RocksDB-backed credential service. On failure: send CONNACK with appropriate return code and close channel.
- **D-05:** Copy and adapt TBMQ's `BasicMqttClientAuthProvider` and `SslMqttClientAuthProvider` patterns. Lightweight does not need TBMQ's pluggable multi-provider chain (MqttAuthProviderManagerService) — two hardcoded providers (basic + SSL) are sufficient for R1.
- **D-06:** Anonymous access controlled by `TBMQ_SECURITY_ANONYMOUS_ENABLED` env var (default: `false` per AUTH-04). When enabled, clients connecting without credentials are allowed. When disabled (default), connections without valid credentials receive CONNACK with `NOT_AUTHORIZED` (0x05 for MQTT 3.1.1) and are disconnected.
- **D-07:** Password hashing uses bcrypt (matching TBMQ's approach via Spring Security's `BCryptPasswordEncoder`). No plain-text password storage.

### Authorization / ACL (AUTH-03)
- **D-08:** ACL rules stored in RocksDB `ACL_RULES` column family (already defined in Phase 1). Key: credential identifier (same as CREDENTIALS key). Value: JSON-serialized ACL rule set with separate publish and subscribe pattern lists.
- **D-09:** Topic patterns use regex matching, consistent with TBMQ's `AuthorizationRuleService`. Each ACL entry contains: `pubPatterns` (list of regex strings for allowed publish topics) and `subPatterns` (list of regex strings for allowed subscribe topic filters).
- **D-10:** Enforcement is deny-by-default: if a client has credentials but no ACL rules, publish and subscribe to all topics are denied. If ACL rules exist, only matching patterns are allowed.
- **D-11:** ACL checked at two points: (1) on SUBSCRIBE — reject topic filters that don't match any `subPatterns`, (2) on PUBLISH — drop messages to topics that don't match any `pubPatterns`. Both log at DEBUG level and increment a Micrometer counter (`mqtt.auth.denied.total` with `type` tag: `publish` or `subscribe`).
- **D-12:** When anonymous access is enabled (D-06), anonymous clients bypass ACL checks entirely (no credentials = no ACL lookup). This matches development/evaluation use case where security is intentionally relaxed.

### TLS Configuration (TRAN-02, AUTH-02)
- **D-13:** TLS listener on port 8883 (default, configurable via `TBMQ_MQTTS_PORT`). Separate Netty bootstrap from the plain TCP listener (port 1883). TLS is optional — disabled by default, enabled when certificate files are configured.
- **D-14:** PEM certificate format — server certificate and private key mounted via Docker volume. Configuration via env vars: `TBMQ_TLS_CERT_PATH`, `TBMQ_TLS_KEY_PATH`. Copy and adapt TBMQ's `PemSslCredentials` pattern for loading PEM files into Netty's `SslContext`.
- **D-15:** Mutual TLS (mTLS) for X.509 client auth — optional, enabled via `TBMQ_TLS_CLIENT_AUTH=REQUIRED` or `OPTIONAL`. Trust store for client CA certificates configured via `TBMQ_TLS_TRUST_CERT_PATH`. When enabled, Netty's `SslHandler` extracts the peer certificate chain, and the SSL auth provider validates the CN/fingerprint against RocksDB credentials.
- **D-16:** Copy and adapt TBMQ's `MqttSslServerBootstrap`, `MqttSslHandlerProvider`, and `MqttSslChannelInitializer` patterns for the lightweight TLS listener. Strip cluster-specific and WebSocket-specific code.

### Claude's Discretion
- Exact RocksDB serialization format for credentials and ACLs (as long as JSON per D-01)
- BCrypt strength parameter (TBMQ default is fine)
- Credential service interface design (follow `Default` prefix naming convention)
- ACL cache strategy (Caffeine cache for hot-path ACL lookups — size and TTL at Claude's discretion)
- Integration test design for auth scenarios
- Whether to add a credential management CLI or REST endpoint in this phase (not required by requirements, but could be useful)
- TLS cipher suite configuration (sensible defaults from Netty/JDK)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### TBMQ Authentication Patterns (copy and adapt)
- `application/src/main/java/org/thingsboard/mqtt/broker/service/auth/providers/basic/BasicMqttClientAuthProvider.java` — Basic (username/password) auth provider
- `application/src/main/java/org/thingsboard/mqtt/broker/service/auth/providers/ssl/SslMqttClientAuthProvider.java` — SSL/X.509 client cert auth provider
- `application/src/main/java/org/thingsboard/mqtt/broker/service/auth/AuthorizationRuleService.java` — Authorization rule service interface
- `application/src/main/java/org/thingsboard/mqtt/broker/service/auth/DefaultAuthorizationRuleService.java` — Regex-based ACL enforcement
- `application/src/main/java/org/thingsboard/mqtt/broker/service/mqtt/auth/MqttAuthProviderManagerService.java` — Auth provider orchestration (simplify for R1)

### TBMQ TLS/SSL Patterns (copy and adapt)
- `application/src/main/java/org/thingsboard/mqtt/broker/server/tls/MqttSslServerBootstrap.java` — TLS Netty server bootstrap
- `application/src/main/java/org/thingsboard/mqtt/broker/server/tls/MqttSslHandlerProvider.java` — SSL handler factory
- `application/src/main/java/org/thingsboard/mqtt/broker/server/tls/MqttSslChannelInitializer.java` — TLS channel pipeline
- `application/src/main/java/org/thingsboard/mqtt/broker/ssl/config/PemSslCredentials.java` — PEM file loading
- `application/src/main/java/org/thingsboard/mqtt/broker/ssl/config/SslCredentials.java` — SSL credentials interface
- `application/src/main/java/org/thingsboard/mqtt/broker/ssl/config/AbstractSslCredentials.java` — SSL credentials base

### TBMQ Credential Models (copy and adapt)
- `dao/src/main/java/org/thingsboard/mqtt/broker/dao/model/MqttClientCredentialsEntity.java` — Credential entity model
- `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/client/credentials/BasicMqttCredentials.java` — Basic credentials model
- `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/client/credentials/SslMqttCredentials.java` — SSL credentials model

### Lightweight Current Code (to extend)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/actors/client/ClientActor.java` — Add auth check in processConnect()
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/storage/rocksdb/DefaultRocksDbStorage.java` — RocksDB storage service
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/storage/rocksdb/RocksDbColumnFamily.java` — CREDENTIALS and ACL_RULES column families
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/MqttChannelInitializer.java` — Netty pipeline (TCP)
- `lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/StorageConfiguration.java` — RocksDB config
- `lightweight/src/main/resources/tbmq-lightweight.yml` — Configuration file

### Architecture & Planning
- `.planning/PROJECT.md` — Core value, constraints, key decisions
- `.planning/REQUIREMENTS.md` — AUTH-01 through AUTH-05, TRAN-02
- `.planning/phases/01-foundation/01-CONTEXT.md` — RocksDB schema decisions (D-04 through D-07)
- `.planning/phases/02-core-protocol-mqtt-3-1-1/02-CONTEXT.md` — MQTT protocol decisions
- `.planning/phases/03-message-dispatch/03-CONTEXT.md` — Dispatch architecture decisions

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `DefaultRocksDbStorage` — RocksDB service with get/put/delete/scan operations, ready to use for credentials and ACLs
- `RocksDbColumnFamily.CREDENTIALS` and `RocksDbColumnFamily.ACL_RULES` — Column families already defined and opened
- `BrokerMetricsService` — Micrometer registry for auth denial counters
- `ClientActor.processConnect()` — CONNECT processing where auth check will be inserted
- `MqttChannelInitializer` — Netty pipeline to clone for TLS variant
- `MqttTcpServerBootstrap` — TCP bootstrap pattern to follow for TLS bootstrap
- TBMQ's `BasicMqttClientAuthProvider` — Battle-tested basic auth with bcrypt
- TBMQ's `PemSslCredentials` — PEM file loading for Netty SslContext
- TBMQ's `AuthorizationRuleService` — Regex-based topic ACL enforcement

### Established Patterns
- Copy from TBMQ and trim (per user feedback) — strip PostgreSQL/JPA/Kafka, keep business logic
- Interface + `Default` prefix implementation naming
- Spring `@Component`/`@Service` with `@RequiredArgsConstructor`
- Configuration via `@Value("${tbmq.property:default}")` with env var overrides
- SmartLifecycle for ordered startup/shutdown (RocksDB first, services after)
- Jackson JSON serialization for RocksDB values (per Phase 1 D-05)

### Integration Points
- `ClientActor.processConnect()` — Insert credential validation before CONNACK
- `ClientActor.processSubscribe()` — Insert ACL check before SUBACK
- `ClientActor.processPublish()` — Insert ACL check before dispatch
- `MqttChannelInitializer` — Basis for TLS channel initializer
- `MqttTcpServerBootstrap` — Pattern for TLS server bootstrap
- `tbmq-lightweight.yml` — Add security and TLS config sections

</code_context>

<specifics>
## Specific Ideas

- TBMQ uses a pluggable auth provider chain (`MqttAuthProviderManagerService`) that iterates providers until one succeeds. Lightweight R1 simplifies this to two hardcoded providers: basic (username/password) and SSL (X.509 certificate). No need for the full provider management infrastructure.
- RocksDB column families for CREDENTIALS and ACL_RULES are already opened but empty. Phase 4 needs to implement the CRUD service layer and an install-time data loader for default credentials.
- TLS listener should be a separate Netty bootstrap on port 8883, not a mode switch on the existing TCP listener. This matches TBMQ's architecture and allows running both plain and TLS simultaneously.
- For mTLS, Netty's SslHandler provides the peer certificate chain via `sslHandler.engine().getSession().getPeerCertificates()`. The SSL auth provider extracts the CN or computes the SHA-256 fingerprint and matches against RocksDB credentials.
- BCrypt for password hashing matches TBMQ and Spring Security conventions. Spring Security's `BCryptPasswordEncoder` is already on the classpath via Spring Boot Starter Security (if added as dependency).

</specifics>

<deferred>
## Deferred Ideas

- REST API for credential/ACL management (MGMT-01, deferred to R2)
- Web UI for auth configuration (MGMT-02, deferred to R2)
- Hot-reload of credentials without restart (MGMT-03, deferred to R2)
- SCRAM enhanced authentication (MQTT 5.0 feature, Phase 6)
- HTTP external auth provider (not needed for R1 standalone deployment)
- JWT MQTT auth provider (enterprise feature, not in scope)

</deferred>

---

*Phase: 04-security*
*Context gathered: 2026-04-08*
