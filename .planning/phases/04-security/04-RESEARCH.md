# Phase 04: Security - Research

**Researched:** 2026-04-08
**Domain:** MQTT broker security — authentication, authorization, TLS/mTLS, RocksDB-backed credentials
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Credentials stored in RocksDB `CREDENTIALS` column family. Key: credential identifier (username for basic auth, CN/fingerprint for X.509). Value: JSON-serialized credential object (Jackson).
- **D-02:** Two credential types: `BASIC` (username + bcrypt-hashed password) and `SSL` (X.509 certificate CN or SHA-256 fingerprint). Copy TBMQ's `MqttClientCredentials` model and `BasicCredentials`/`SslCredentials` patterns, trimming PostgreSQL/JPA dependencies.
- **D-03:** Default admin credentials pre-loaded on first start (install profile). Username: `tbmq`, password: `tbmq`.
- **D-04:** Authentication at actor level during CONNECT processing in ClientActor. On failure: send CONNACK with appropriate return code and close channel.
- **D-05:** Copy and adapt TBMQ's `BasicMqttClientAuthProvider` and `SslMqttClientAuthProvider`. Two hardcoded providers (basic + SSL), no pluggable chain needed for R1.
- **D-06:** Anonymous access controlled by `TBMQ_SECURITY_ANONYMOUS_ENABLED` env var (default: `false`). When disabled, clients without valid credentials receive CONNACK `NOT_AUTHORIZED` (0x05) and are disconnected.
- **D-07:** Password hashing uses bcrypt via Spring Security's `BCryptPasswordEncoder`. No plain-text password storage.
- **D-08:** ACL rules stored in RocksDB `ACL_RULES` column family. Key: credential identifier. Value: JSON-serialized ACL rule set with `pubPatterns` and `subPatterns` (regex lists).
- **D-09:** Topic patterns use regex matching, consistent with TBMQ's `AuthorizationRuleService`.
- **D-10:** Deny-by-default ACL: credentials with no ACL rules are denied all publish/subscribe.
- **D-11:** ACL checked at SUBSCRIBE (topic filter) and PUBLISH (topic). Denials log at DEBUG + increment Micrometer counter (`mqtt.auth.denied.total` with `type` tag: `publish` or `subscribe`).
- **D-12:** When anonymous access is enabled, anonymous clients bypass ACL checks entirely.
- **D-13:** TLS listener on port 8883 (default: `TBMQ_MQTTS_PORT`). Separate Netty bootstrap. Disabled by default, enabled when certificate files are configured.
- **D-14:** PEM certificate format. Configuration via `TBMQ_TLS_CERT_PATH`, `TBMQ_TLS_KEY_PATH`. Copy and adapt TBMQ's `PemSslCredentials`.
- **D-15:** Mutual TLS optional — `TBMQ_TLS_CLIENT_AUTH=REQUIRED` or `OPTIONAL`. Trust store for client CAs: `TBMQ_TLS_TRUST_CERT_PATH`. SSL auth provider validates CN/fingerprint against RocksDB credentials.
- **D-16:** Copy and adapt TBMQ's `MqttSslServerBootstrap`, `MqttSslHandlerProvider`, `MqttSslChannelInitializer`.

### Claude's Discretion
- Exact RocksDB serialization format for credentials and ACLs (as long as JSON per D-01)
- BCrypt strength parameter (TBMQ default is fine)
- Credential service interface design (follow `Default` prefix naming convention)
- ACL cache strategy (Caffeine cache for hot-path ACL lookups — size and TTL at Claude's discretion)
- Integration test design for auth scenarios
- Whether to add a credential management CLI or REST endpoint in this phase (not required by requirements, but could be useful)
- TLS cipher suite configuration (sensible defaults from Netty/JDK)

### Deferred Ideas (OUT OF SCOPE)
- REST API for credential/ACL management (MGMT-01, deferred to R2)
- Web UI for auth configuration (MGMT-02, deferred to R2)
- Hot-reload of credentials without restart (MGMT-03, deferred to R2)
- SCRAM enhanced authentication (MQTT 5.0 feature, Phase 6)
- HTTP external auth provider (not needed for R1 standalone deployment)
- JWT MQTT auth provider (enterprise feature, not in scope)
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AUTH-01 | Broker authenticates clients via username/password credentials stored in RocksDB | Credential service backed by `DefaultRocksDbStorage.get()` in `CREDENTIALS` CF; BCryptPasswordEncoder for password verification |
| AUTH-02 | Broker authenticates clients via X.509 client certificates during mutual TLS handshake | Netty `SslHandler.engine().getSession().getPeerCertificates()`; CN extraction via BouncyCastle `SslUtil.parseCommonName()`; match against `CREDENTIALS` CF |
| AUTH-03 | Broker enforces topic-level ACL rules (publish and subscribe permissions) stored in RocksDB | `DefaultAuthorizationRuleService.isPubAuthorized()` and `isSubAuthorized()` with compiled regex `Pattern` list from `ACL_RULES` CF |
| AUTH-04 | Broker requires authentication by default — anonymous access disabled unless explicitly configured | `TBMQ_SECURITY_ANONYMOUS_ENABLED` env var → `@Value` property; checked in `ClientActor.processConnect()` before RocksDB lookup |
| AUTH-05 | Credentials and ACL rules persist across broker restarts via RocksDB storage | Already-opened `CREDENTIALS` and `ACL_RULES` column families in `DefaultRocksDbStorage`; default credentials written on first startup via `@PostConstruct` check |
| TRAN-02 | Broker supports TLS-encrypted MQTT connections via mounted server certificate and key files | Separate `MqttSslServerBootstrap` (SmartLifecycle phase > 0); `PemSslCredentials` loading BouncyCastle PEM parser; conditional on cert path being set |
</phase_requirements>

---

## Summary

Phase 4 adds the full security stack to TBMQ Lightweight. The implementation follows a copy-and-trim strategy from the parent TBMQ codebase, adapting battle-tested auth providers and TLS infrastructure while removing PostgreSQL/JPA/Redis dependencies. All state is backed by RocksDB column families already opened in Phase 1.

The core work splits into three independent tracks: (1) the credential and ACL service layer backed by RocksDB, (2) the auth pipeline wired into `ClientActor.processConnect()` / `processSubscribe()` / `processPublish()`, and (3) the TLS listener stack (a second Netty bootstrap conditionally activated when cert paths are configured). The tracks share the credential service but are otherwise orthogonal, enabling wave-based delivery.

Default credentials (`tbmq`/`tbmq`) must be pre-loaded at first startup so the broker is usable immediately after `docker run`. The pattern is a `@PostConstruct` check in the credential service (or a dedicated installer bean) that writes defaults only when `CREDENTIALS` CF is empty — this matches the TBMQ install-profile pattern without introducing a separate profile for R1.

**Primary recommendation:** Copy TBMQ's `BasicMqttClientAuthProvider`, `SslMqttClientAuthProvider`, `DefaultAuthorizationRuleService`, `PemSslCredentials`, and `MqttSslServerBootstrap` verbatim; strip PostgreSQL/JPA/Redis/Kafka wiring; replace cache lookups with direct RocksDB reads wrapped in Caffeine cache; wire auth checks into `ClientActor` at the three integration points.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Security `BCryptPasswordEncoder` | Managed by Spring Boot 3.5.3 | Password hashing and verification | Already pulled in transitively; matches TBMQ pattern; constant-time comparison prevents timing attacks |
| Netty `netty-handler` (SslHandler) | 4.1.122.Final (pinned) | TLS termination, client cert extraction | Netty native TLS; `SslHandler.engine().getSession().getPeerCertificates()` is the peer cert API |
| BouncyCastle `bcprov-jdk18on` + `bcpkix-jdk18on` | 1.79 (from TBMQ parent POM) | PEM file parsing, CN extraction from X.509 certs | TBMQ's `PemSslCredentials` and `SslUtil.parseCommonName()` both require BouncyCastle; JDK PEM support is limited |
| Jackson `ObjectMapper` | Managed by Spring Boot 3.5.3 | JSON serialization of credentials and ACL rules to RocksDB values | Already on classpath; matches D-01 decision |
| Caffeine | Managed by Spring Boot 3.5.3 | In-process cache for credential and ACL hot paths | Already configured in `CacheConfiguration`; caches `credentials` and `acl_rules` already named |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Netty `SslContext` (via `SslContextBuilder`) | 4.1.122.Final | Building server-side TLS context from KeyManager/TrustManager | Used by the TLS channel initializer to create per-channel `SslHandler` |
| Eclipse Paho MQTT v3 | 1.2.5 (test scope, already in pom) | TLS integration test client with `SSLSocketFactory` | Paho supports TLS via `MqttConnectOptions.setSocketFactory()` — suitable for MQTTS integration tests |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `BCryptPasswordEncoder` (Spring Security) | Raw JDK `MessageDigest` SHA-256 | BCrypt is time-hardened (work factor); SHA-256 is fast = brute-force vulnerable. Use BCrypt. |
| BouncyCastle PEM parser | JDK `PEMReader` (removed in JDK 9) or OpenSSL CLI | BouncyCastle is the only portable Java PEM parser; TBMQ already has the dependency |
| Caffeine in-process cache | No cache, direct RocksDB read per CONNECT | RocksDB reads are fast (microseconds) but add latency per auth; Caffeine eliminates repeated disk reads for hot credentials |

**Installation — dependencies to add to `lightweight/pom.xml`:**
```xml
<!-- Spring Security (for BCryptPasswordEncoder) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- BouncyCastle for PEM file parsing and CN extraction -->
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcprov-jdk18on</artifactId>
    <version>1.79</version>
</dependency>
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcpkix-jdk18on</artifactId>
    <version>1.79</version>
</dependency>
```

**Spring Security caveat:** Adding `spring-boot-starter-security` will auto-configure HTTP Basic auth on the Actuator endpoints. The lightweight app must either disable HTTP security for actuator or configure a `SecurityFilterChain` bean to permit `/actuator/**` without authentication. TBMQ's `SecurityConfiguration` is the reference — a minimal version that permits all actuator endpoints is sufficient for R1.

---

## Architecture Patterns

### Recommended Project Structure (new packages for Phase 4)
```
lightweight/src/main/java/.../lightweight/
├── security/
│   ├── auth/
│   │   ├── LightweightCredentialService.java          # interface
│   │   ├── DefaultLightweightCredentialService.java   # RocksDB-backed implementation
│   │   ├── LightweightAclService.java                 # interface
│   │   ├── DefaultLightweightAclService.java          # RocksDB-backed + Caffeine
│   │   ├── LightweightAuthProvider.java               # interface: authenticate(AuthContext) -> AuthResult
│   │   ├── BasicLightweightAuthProvider.java          # username/password against CREDENTIALS CF
│   │   └── SslLightweightAuthProvider.java            # X.509 CN against CREDENTIALS CF
│   └── acl/
│       ├── AuthorizationRuleService.java              # interface (copy from TBMQ)
│       └── DefaultAuthorizationRuleService.java       # regex-based (copy from TBMQ, strip deps)
├── server/
│   └── tls/
│       ├── MqttSslServerBootstrap.java                # TLS Netty bootstrap (SmartLifecycle phase 1)
│       ├── MqttSslChannelInitializer.java             # TLS pipeline
│       └── MqttSslHandlerProvider.java                # SslHandler factory from PEM creds
├── ssl/
│   ├── PemSslCredentials.java                        # PEM file loading (copy from TBMQ)
│   ├── AbstractSslCredentials.java                   # KeyStore/TrustManager logic (copy)
│   └── SslUtil.java                                  # CN parsing (copy from TBMQ)
├── config/
│   └── SecurityConfiguration.java                   # Spring Security: permit actuator, disable form login
└── install/
    └── DefaultCredentialsInstaller.java              # @PostConstruct: write tbmq/tbmq if CREDENTIALS empty
```

### Pattern 1: Credential Service (RocksDB-backed with Caffeine)
**What:** Service reads/writes JSON-serialized credential objects from the `CREDENTIALS` column family. Caffeine cache wraps the read path to avoid RocksDB I/O on every CONNECT.
**When to use:** Every CONNECT packet triggers credential lookup.
```java
// Source: adapted from DefaultRocksDbStorage usage pattern (Phase 1)
@Service
@RequiredArgsConstructor
public class DefaultLightweightCredentialService implements LightweightCredentialService {

    private final RocksDbStorage storage;
    private final ObjectMapper objectMapper;

    @Override
    @Cacheable(value = "credentials", key = "#credentialId")
    public LightweightCredential findByCredentialId(String credentialId) {
        String json = storage.get(RocksDbColumnFamily.CREDENTIALS, credentialId);
        if (json == null) return null;
        return objectMapper.readValue(json, LightweightCredential.class);
    }

    @Override
    @CacheEvict(value = "credentials", key = "#credential.credentialId")
    public void saveCredential(LightweightCredential credential) {
        storage.put(RocksDbColumnFamily.CREDENTIALS, credential.getCredentialId(),
                objectMapper.writeValueAsString(credential));
    }
}
```

### Pattern 2: Auth Check in ClientActor.processConnect()
**What:** Before sending CONNACK, extract credentials from the CONNECT message and validate via the auth providers. On failure: send CONNACK `NOT_AUTHORIZED` (0x05) and close the channel.
**When to use:** Every CONNECT processing call.
```java
// Source: adapted from TBMQ ClientActor + BasicMqttClientAuthProvider pattern
private void processConnect(MqttConnectMsg msg) {
    MqttConnectMessage connectMessage = msg.getConnectMessage();

    // Auth check BEFORE session registration
    AuthResult authResult = authenticate(connectMessage, sessionCtx);
    if (!authResult.isSuccess()) {
        log.warn("[{}] Authentication failed: {}", clientId, authResult.getFailureReason());
        sessionCtx.getChannel().writeAndFlush(
                messageGenerator.createConnAck(MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED, false));
        sessionCtx.getChannel().close();
        return;
    }

    // Store auth result on session context for ACL checks
    sessionCtx.setAuthRulePatterns(authResult.getAuthRulePatterns());

    // ... existing session registration logic ...
}
```

### Pattern 3: ACL Enforcement in processPublish() and processSubscribe()
**What:** Check the compiled regex patterns from the auth result before allowing publish/subscribe.
**When to use:** Every PUBLISH and SUBSCRIBE message.
```java
// Source: adapted from TBMQ DefaultAuthorizationRuleService.isPubAuthorized()
private void processPublish(MqttPublishMsg msg) {
    // ACL check for publish
    if (!authorizationRuleService.isPubAuthorized(clientId, msg.getTopicName(),
            sessionCtx.getAuthRulePatterns())) {
        log.debug("[{}] PUBLISH denied to topic '{}' (ACL)", clientId, msg.getTopicName());
        meterRegistry.counter("mqtt.auth.denied.total", "type", "publish").increment();
        return; // drop the message silently
    }
    // ... existing dispatch logic ...
}
```

### Pattern 4: TLS Server Bootstrap (conditional activation)
**What:** A second Netty bootstrap that activates only when `TBMQ_TLS_CERT_PATH` is configured. Uses `ConditionalOnProperty` pattern from TBMQ.
**When to use:** TLS listener on port 8883.
```java
// Source: adapted from TBMQ MqttSslServerBootstrap
@Service
@RequiredArgsConstructor
public class MqttSslServerBootstrap implements SmartLifecycle {

    @Value("${tbmq.tls.enabled:false}")
    private boolean tlsEnabled;

    @Override
    public int getPhase() {
        return 1; // Start after TCP bootstrap (phase 0), stop before TCP bootstrap
    }

    @Override
    public boolean isAutoStartup() {
        return tlsEnabled;
    }
    // ...
}
```

### Pattern 5: Default Credential Installer
**What:** On first startup, if `CREDENTIALS` column family is empty, write the default `tbmq`/`tbmq` credential. Uses `@PostConstruct` with a RocksDB presence check — no Spring profile needed.
**When to use:** First-run initialization per D-03.
```java
// Source: lightweight install pattern (no Spring @Profile needed for R1)
@Service
@RequiredArgsConstructor
public class DefaultCredentialsInstaller {

    private final LightweightCredentialService credentialService;
    private final BCryptPasswordEncoder passwordEncoder;

    @PostConstruct
    public void installDefaultCredentials() {
        if (credentialService.findByCredentialId("tbmq") == null) {
            String hashedPassword = passwordEncoder.encode("tbmq");
            credentialService.saveCredential(new LightweightCredential(
                    "tbmq", CredentialType.BASIC, hashedPassword,
                    PubSubAuthorizationRules.defaultInstance()));
            log.info("Default credentials installed: username=tbmq");
        }
    }
}
```

### Anti-Patterns to Avoid
- **Storing passwords in plain text:** BCrypt hashing is mandatory per D-07. Never store raw passwords in RocksDB.
- **Blocking the actor thread for I/O:** All RocksDB reads for auth must complete synchronously but are fast (microseconds). The Caffeine cache ensures only cold-path reads hit RocksDB. Never use `CompletableFuture.get()` inside the actor.
- **Opening the TLS bootstrap on the same port as TCP:** Separate ports (1883/8883) with separate bootstraps. Attempting SSL on a non-SSL port causes handshake failure before MQTT frame parsing.
- **Using `@Profile("install")` for default credential loading:** For lightweight R1, a `@PostConstruct` presence check is simpler and eliminates the need for a separate install profile/container entrypoint.
- **Ignoring Spring Security auto-config:** Adding `spring-boot-starter-security` will lock down the `/actuator` HTTP endpoints. A minimal `SecurityFilterChain` bean must explicitly permit actuator paths.
- **Compiling ACL patterns on every auth check:** `DefaultAuthorizationRuleService` compiles regex `Pattern` objects at parse time (CONNECT) and stores them on the session. Never re-compile on every PUBLISH/SUBSCRIBE.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Password hashing | Custom PBKDF2/SHA implementation | `BCryptPasswordEncoder` from Spring Security | Timing-safe comparison, work factor, salting all built in |
| PEM file parsing | Manual string splitting / regex | `PemSslCredentials` (copy from TBMQ) using BouncyCastle `PEMParser` | PKCS8, encrypted keys, cert chains, key-from-cert-file — all handled |
| CN extraction from X.509 | `certificate.getSubjectDN().getName()` string parsing | `SslUtil.parseCommonName()` (copy from TBMQ) using BouncyCastle `JcaX509CertificateHolder` | `getSubjectDN()` format is JDK-specific and fragile; BouncyCastle is canonical |
| TLS context building | Manual `KeyManagerFactory` wiring | `AbstractSslCredentials.createKeyManagerFactory()` + `SslContextBuilder` | Chain order, key alias resolution, trust store loading — all edge cases handled in TBMQ |
| Regex topic matching | Simple `String.matches()` per topic | `DefaultAuthorizationRuleService` with compiled `Pattern` objects | Pre-compiled patterns, MQTT topic-to-regex mapping, per-client publish auth cache already implemented |

**Key insight:** TBMQ's auth infrastructure has survived production deployments and edge cases (encrypted PEM keys, regex CN matching, empty credential lists, cache invalidation). Copy the logic; trim the infrastructure wiring.

---

## Common Pitfalls

### Pitfall 1: Spring Security Auto-Config Locks Down Actuator
**What goes wrong:** Adding `spring-boot-starter-security` activates `UserDetailsServiceAutoConfiguration` and generates a random password, and `SecurityAutoConfiguration` secures all HTTP endpoints including `/actuator/health` and `/actuator/prometheus`.
**Why it happens:** Spring Boot security auto-config secures everything by default.
**How to avoid:** Add a `SecurityConfiguration` bean with `SecurityFilterChain` that:
- Disables HTTP Basic for actuator paths: `.requestMatchers("/actuator/**").permitAll()`
- Disables CSRF (broker has no form login)
- Disables Spring's generated random password by providing a `NoOpUserDetailsService` or `@Bean UserDetailsService` that returns empty
**Warning signs:** `/actuator/health` returns 401 after adding the security dependency.

### Pitfall 2: `BCryptPasswordEncoder` Not a Spring Bean
**What goes wrong:** `BCryptPasswordEncoder` is not auto-configured by Spring Boot — unlike in full TBMQ, the lightweight module has no `PasswordEncoderConfig` bean.
**Why it happens:** It must be declared as a `@Bean` explicitly in a `@Configuration` class.
**How to avoid:** Declare `@Bean BCryptPasswordEncoder passwordEncoder()` in `SecurityConfiguration` or a dedicated config bean. The `DefaultCredentialsInstaller` and `BasicLightweightAuthProvider` both need it injected.

### Pitfall 3: TLS Listener Phase Ordering
**What goes wrong:** `MqttSslServerBootstrap` starts before RocksDB and the credential service are available, causing NPE or `IllegalStateException` when loading PEM credentials.
**Why it happens:** Incorrect SmartLifecycle phase number — if phase <= `Integer.MIN_VALUE` (RocksDB's phase), TLS starts in parallel with RocksDB.
**How to avoid:** Set `getPhase()` to `1` for the TLS bootstrap (same logic as TCP bootstrap at phase `0`, but TLS starts after TCP). Both stop before RocksDB (higher phase stops first in reverse order).

### Pitfall 4: Caffeine Cache Miss for SSL Credentials
**What goes wrong:** SSL auth provider caches credentials keyed by CN pattern. If a credential is saved, but the Caffeine cache still holds a null entry (negative cache), auth fails until cache expiry.
**Why it happens:** `@Cacheable` with a null return value caches the null; subsequent lookups return null without hitting RocksDB.
**How to avoid:** Use `@Cacheable(unless = "#result == null")` on the credential service lookup methods to prevent null caching. Or use explicit Caffeine `Cache.getIfPresent()` / `Cache.put()` calls.

### Pitfall 5: MQTT 3.1.1 CONNACK Return Code for Auth Failure
**What goes wrong:** Wrong return code used for auth failures — using `CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD` (0x04) for all failures, including missing credentials.
**Why it happens:** MQTT 3.1.1 has multiple return codes for different failure reasons.
**How to avoid:**
- `CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD` (0x04): credentials present but wrong
- `CONNECTION_REFUSED_NOT_AUTHORIZED` (0x05): credentials missing when anonymous is disabled, or client lacks permission
TBMQ uses `NOT_AUTHORIZED` (0x05) as the catch-all for anonymous rejection; use the same.

### Pitfall 6: Anonymous Check Ordering in ClientActor
**What goes wrong:** Anonymous check runs AFTER credential lookup, causing unnecessary RocksDB reads for clients with no credentials.
**Why it happens:** Code structure puts credential lookup before anonymous check.
**How to avoid:** Check anonymous-enabled flag FIRST. If username/password are null AND anonymous is enabled → allow. If username/password are null AND anonymous is disabled → reject immediately with `NOT_AUTHORIZED`. Only run credential lookup when credentials are actually present.

### Pitfall 7: mTLS Peer Certificate Not Available
**What goes wrong:** `sslHandler.engine().getSession().getPeerCertificates()` throws `SSLPeerUnverifiedException` even when `TBMQ_TLS_CLIENT_AUTH=REQUIRED`.
**Why it happens:** Client auth mode must be set on the `SSLEngine` during TLS context construction, not just on the `SslContext`. If `clientAuth(ClientAuth.REQUIRE)` is not set on `SslContextBuilder`, the handshake completes without requesting a client cert.
**How to avoid:** Set `SslContextBuilder.forServer(...).clientAuth(ClientAuth.REQUIRE)` when `TBMQ_TLS_CLIENT_AUTH=REQUIRED`. Copy `AbstractSslCredentials.createTrustManagerFactory()` for the client CA trust store.

---

## Code Examples

Verified patterns from TBMQ source:

### Credential Model (lightweight — no JPA/PostgreSQL)
```java
// Source: adapted from MqttClientCredentials + BasicMqttCredentials (TBMQ common/data)
// Lightweight version uses no UUID, no BaseData hierarchy
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LightweightCredential {
    private String credentialId;    // RocksDB key (username / CN)
    private CredentialType type;    // BASIC or SSL
    private String credentialValue; // JSON: BasicMqttCredentials or SslMqttCredentials
    // authRules embedded in credentialValue (matches TBMQ BasicMqttCredentials.authRules)
}
```

### Basic Auth Flow (extracted from TBMQ BasicMqttClientAuthProvider)
```java
// Source: BasicMqttClientAuthProvider.authWithBasicCredentials()
// Lightweight simplification: single credentialId lookup (no multi-strategy)
String credentialId = username; // or clientId — strategy from config
String json = credentialService.findByCredentialId(credentialId);
if (json == null) return AuthResult.failure("No credentials found");
BasicMqttCredentials creds = objectMapper.readValue(json, BasicMqttCredentials.class);
if (creds.getPassword() == null || passwordEncoder.matches(password, creds.getPassword())) {
    return AuthResult.success(authorizationRuleService.parseAuthorizationRule(creds));
}
return AuthResult.failure("Password mismatch");
```

### SSL Auth Flow (extracted from TBMQ SslMqttClientAuthProvider)
```java
// Source: SslMqttClientAuthProvider.authWithSSLCredentials()
X509Certificate[] certs = (X509Certificate[])
    sslHandler.engine().getSession().getPeerCertificates();
String cn = SslUtil.parseCommonName(certs[0]);
String credentialId = cn; // lookup by CN
String json = credentialService.findByCredentialId(credentialId);
if (json == null) return AuthResult.failure("No X.509 credentials for CN: " + cn);
SslMqttCredentials creds = objectMapper.readValue(json, SslMqttCredentials.class);
// authRulesMapping: Map<String regex, PubSubAuthorizationRules>
return AuthResult.success(authorizationRuleService.parseSslAuthorizationRule(creds, cn));
```

### ACL isPubAuthorized (from DefaultAuthorizationRuleService)
```java
// Source: DefaultAuthorizationRuleService.isPubAuthorized() — copy verbatim
// publishAuthMap: ConcurrentMap<clientId, ConcurrentMap<topic, Boolean>>
// Pre-caches per-client topic authorization results to avoid regex matching on every message
public boolean isPubAuthorized(String clientId, String topic, List<AuthRulePatterns> patterns) {
    if (CollectionUtils.isEmpty(patterns)) return true; // anonymous / no rules = allow all
    return publishAuthMap.computeIfAbsent(clientId, s -> new ConcurrentHashMap<>())
        .computeIfAbsent(topic, s -> patterns.stream()
            .flatMap(p -> p.getPubPatterns().stream())
            .anyMatch(pattern -> pattern.matcher(topic).matches()));
}
```

### PEM Loading for TLS (from AbstractSslCredentials + PemSslCredentials)
```java
// Source: PemSslCredentials.loadKeyStore() — copy verbatim
// Handles: X509CertificateHolder, PEMEncryptedKeyPair, PEMKeyPair,
//          PrivateKeyInfo, PKCS8EncryptedPrivateKeyInfo
// Returns a KeyStore ready for KeyManagerFactory.init()
```

### SslContextBuilder for Netty TLS
```java
// Source: AbstractMqttHandlerProvider (TBMQ) — adapted for lightweight
SslContext sslContext = SslContextBuilder
    .forServer(kmf)           // KeyManagerFactory from PemSslCredentials
    .trustManager(tmf)        // TrustManagerFactory for mTLS (optional)
    .clientAuth(clientAuth)   // ClientAuth.NONE/OPTIONAL/REQUIRE
    .protocols("TLSv1.2", "TLSv1.3")
    .build();
// Returns SslHandler via sslContext.newHandler(channel.alloc())
```

---

## Environment Availability

Step 2.6: Dependencies for this phase are all JVM-library-based. No external services needed.

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Spring Security `BCryptPasswordEncoder` | AUTH-01, D-07 | ✓ (add dep) | Managed by Spring Boot 3.5.3 | None — required |
| BouncyCastle `bcpkix-jdk18on` | AUTH-02, TRAN-02 (PEM + CN) | ✓ (add dep) | 1.79 (match TBMQ) | None — JDK PEM parsing is inadequate |
| Netty `SslHandler` | TRAN-02, AUTH-02 | ✓ (netty-all 4.1.122.Final in pom) | 4.1.122.Final | — |
| Caffeine cache | ACL hot path | ✓ (in pom already) | Managed by Spring Boot | — |
| RocksDB `CREDENTIALS` + `ACL_RULES` CFs | AUTH-01, AUTH-03, AUTH-05 | ✓ (Phase 1 complete) | 9.7.4 | — |
| Eclipse Paho v3 (test) | Integration tests | ✓ (in pom test scope) | 1.2.5 | — |

**Missing dependencies with no fallback:**
- `spring-boot-starter-security` — must be added to `lightweight/pom.xml`
- `bcprov-jdk18on` + `bcpkix-jdk18on` — must be added to `lightweight/pom.xml`

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Spring Boot Test), Surefire 3.2.5 |
| Config file | `lightweight/pom.xml` surefire configuration |
| Quick run command | `mvn -pl lightweight test -Dtest="*Auth*,*Security*,*Tls*" -q` |
| Full suite command | `mvn -pl lightweight test -q` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| AUTH-01 | Valid username/password → CONNACK ACCEPTED | Integration | `mvn -pl lightweight test -Dtest="MqttAuthIntegrationTest#testConnect_withValidCredentials*" -q` | ❌ Wave 0 |
| AUTH-01 | Invalid password → CONNACK NOT_AUTHORIZED | Integration | `mvn -pl lightweight test -Dtest="MqttAuthIntegrationTest#testConnect_withInvalidPassword*" -q` | ❌ Wave 0 |
| AUTH-02 | Valid X.509 client cert → mTLS CONNACK ACCEPTED | Integration | `mvn -pl lightweight test -Dtest="MqttTlsAuthIntegrationTest#testConnect_withValidClientCert*" -q` | ❌ Wave 0 |
| AUTH-03 | PUBLISH to unauthorized topic → message dropped | Integration | `mvn -pl lightweight test -Dtest="MqttAclIntegrationTest#testPublish_deniedTopic*" -q` | ❌ Wave 0 |
| AUTH-03 | SUBSCRIBE to unauthorized topic → failure return code | Integration | `mvn -pl lightweight test -Dtest="MqttAclIntegrationTest#testSubscribe_deniedTopic*" -q` | ❌ Wave 0 |
| AUTH-04 | Anonymous connect → CONNACK NOT_AUTHORIZED (default) | Integration | `mvn -pl lightweight test -Dtest="MqttAuthIntegrationTest#testConnect_anonymous_defaultDenied*" -q` | ❌ Wave 0 |
| AUTH-04 | Anonymous connect with `TBMQ_SECURITY_ANONYMOUS_ENABLED=true` → ACCEPTED | Integration | `mvn -pl lightweight test -Dtest="MqttAuthIntegrationTest#testConnect_anonymous_whenEnabled*" -q` | ❌ Wave 0 |
| AUTH-05 | Credentials loaded from RocksDB survive restart | Integration | `mvn -pl lightweight test -Dtest="MqttAuthIntegrationTest#testCredentials_persistedAcrossContextRestart*" -q` | ❌ Wave 0 |
| TRAN-02 | TLS CONNECT with server cert → CONNACK ACCEPTED | Integration | `mvn -pl lightweight test -Dtest="MqttTlsIntegrationTest#testConnect_tlsWithServerCert*" -q` | ❌ Wave 0 |

### Wave 0 Gaps
- [ ] `lightweight/src/test/java/.../mqtt/MqttAuthIntegrationTest.java` — AUTH-01, AUTH-04, AUTH-05
- [ ] `lightweight/src/test/java/.../mqtt/MqttAclIntegrationTest.java` — AUTH-03
- [ ] `lightweight/src/test/java/.../mqtt/MqttTlsIntegrationTest.java` — TRAN-02
- [ ] `lightweight/src/test/java/.../mqtt/MqttTlsAuthIntegrationTest.java` — AUTH-02
- [ ] Test PKI helper: self-signed CA + server cert + client cert generation (either pre-generated PEM files in `src/test/resources/` or programmatic via BouncyCastle in test setup)

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| MQTT auth as an afterthought (no default creds, open access) | Auth-on-by-default, default creds pre-loaded | Phase 4 design decision | Broker is secure by default without manual config |
| Separate install profile for schema init | `@PostConstruct` check for first-run default credentials | Lightweight simplification | No Docker entrypoint script required |
| Redis cache for credentials | Caffeine in-process cache (already configured) | Phase 1 infrastructure decision | Zero external deps; cache already has `credentials` and `acl_rules` cache names pre-declared |

---

## Open Questions

1. **Credential key format for BASIC auth: username or clientId or mixed?**
   - What we know: TBMQ's `BasicMqttClientAuthProvider` supports three strategies: `CLIENT_ID`, `USERNAME`, `CLIENT_ID_AND_USERNAME`. For lightweight R1, simpler is better.
   - What's unclear: Should we support mixed credentials (username+clientId combination)?
   - Recommendation: R1 supports `USERNAME` strategy only — lookup key is the username from the CONNECT packet. clientId-only credentials can be added in R2. This keeps the credential model simple and the default `tbmq`/`tbmq` credential unambiguous.

2. **TLS test infrastructure — programmatic or pre-generated PEM files?**
   - What we know: TLS integration tests require a self-signed CA, server cert, and (for mTLS) a client cert.
   - What's unclear: Whether to generate these programmatically in tests (using BouncyCastle) or use pre-generated PEM files in `src/test/resources/`.
   - Recommendation: Pre-generated PEM files in `src/test/resources/tls/` (created once with `openssl`). Programmatic generation is complex and adds test surface. BouncyCastle in tests adds ~200 lines of cert generation boilerplate.

3. **SUBACK return code for ACL-denied subscriptions**
   - What we know: MQTT 3.1.1 SUBACK uses return codes per subscription: 0x00 (QoS 0), 0x01 (QoS 1), 0x02 (QoS 2), or 0x80 (failure). Per D-11, ACL-denied subscriptions should use a denial return code.
   - What's unclear: Whether to send `0x80` (failure) or silently grant but not deliver (TBMQ sends 0x80 for denied topics).
   - Recommendation: Send `0x80` (failure) per MQTT 3.1.1 spec. This is the correct indicator for "subscription not authorized."

---

## Sources

### Primary (HIGH confidence)
- TBMQ source: `BasicMqttClientAuthProvider.java` — complete basic auth flow with bcrypt, cache, multi-strategy
- TBMQ source: `SslMqttClientAuthProvider.java` — complete SSL auth flow with peer cert extraction, CN matching, regex creds
- TBMQ source: `DefaultAuthorizationRuleService.java` — regex ACL enforcement, publish auth cache, eviction
- TBMQ source: `PemSslCredentials.java` — PEM file loading with BouncyCastle for all key formats
- TBMQ source: `AbstractSslCredentials.java` — KeyStore/KeyManagerFactory/TrustManagerFactory creation
- TBMQ source: `MqttSslServerBootstrap.java` — TLS Netty bootstrap with `ConditionalOnProperty`
- TBMQ source: `MqttSslChannelInitializer.java` — TLS pipeline with SslHandler injection
- TBMQ source: `SslUtil.java` — BouncyCastle CN extraction from X509Certificate
- Lightweight source: `ClientActor.java` — integration points for auth (processConnect, processPublish, processSubscribe)
- Lightweight source: `DefaultRocksDbStorage.java` — RocksDB API; CREDENTIALS and ACL_RULES CFs already open
- Lightweight source: `CacheConfiguration.java` — confirms `credentials` and `acl_rules` cache names pre-declared

### Secondary (MEDIUM confidence)
- MQTT 3.1.1 spec (section 3.2.2.3): CONNACK return codes — 0x04 bad credentials, 0x05 not authorized
- MQTT 3.1.1 spec (section 3.9.3): SUBACK return codes — 0x80 for failure

### Tertiary (LOW confidence — not separately verified)
- Spring Boot Security auto-config behavior with `spring-boot-starter-security` addition — known pattern but not verified against Spring Boot 3.5.3 specifically

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — dependencies directly verified in TBMQ pom.xml and source files
- Architecture: HIGH — all integration points verified by reading existing lightweight source
- Pitfalls: HIGH — Spring Security auto-config and phase ordering are well-documented known issues encountered in Phase 1/2

**Research date:** 2026-04-08
**Valid until:** 2026-05-08 (30 days — Spring Boot / BouncyCastle APIs are stable)
