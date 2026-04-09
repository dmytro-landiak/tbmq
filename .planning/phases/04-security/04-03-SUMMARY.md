---
phase: 04-security
plan: 03
subsystem: auth
tags: [tls, mtls, ssl, netty, bouncycastle, pem, x509, mqtt]

# Dependency graph
requires:
  - phase: 04-01
    provides: RocksDB credential service and SSL credential type (CredentialType.SSL, SslMqttCredentials)
  - phase: 04-02
    provides: Auth pipeline in ClientActor processing SslHandler peer certificates for mTLS
provides:
  - TLS listener on port 8883 (conditional on tbmq.tls.enabled=true)
  - PEM certificate loading via BouncyCastle (all key formats, classpath: support)
  - mTLS client certificate authentication using CN extracted from X.509 peer cert
  - SslContext cached at startup, SslHandler per connection via MqttSslHandlerProvider
  - Test PKI infrastructure (CA, server cert CN=localhost, client cert CN=test-client)
  - Integration tests for TLS-only and mTLS modes
affects: [05-websocket, docker, deployment]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "SmartLifecycle phase=1 for TLS bootstrap (starts after TCP bootstrap at phase=0)"
    - "classpath: prefix in PEM paths for test resource loading without filesystem dependency"
    - "SslContext cached at @PostConstruct, SslHandler created per connection"
    - "ClientAuth enum (NONE/OPTIONAL/REQUIRE) maps from tbmq.tls.client-auth string config"
    - "reuseForks=true in Surefire to prevent OOM from multiple heavyweight Spring Boot contexts"

key-files:
  created:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/ssl/SslCredentials.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/ssl/AbstractSslCredentials.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/ssl/PemSslCredentials.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/ssl/SslUtil.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/TlsConfiguration.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/tls/MqttSslHandlerProvider.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/tls/MqttSslChannelInitializer.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/server/tls/MqttSslServerBootstrap.java
    - lightweight/src/test/resources/tls/ca.pem
    - lightweight/src/test/resources/tls/server.pem
    - lightweight/src/test/resources/tls/server-key.pem
    - lightweight/src/test/resources/tls/client.pem
    - lightweight/src/test/resources/tls/client-key.pem
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttTlsIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttMtlsIntegrationTest.java
  modified:
    - lightweight/pom.xml

key-decisions:
  - "SmartLifecycle phase=1 for TLS bootstrap so it starts after TCP bootstrap (phase=0)"
  - "SslContext built once at @PostConstruct and cached — avoids PEM re-parsing on every connection"
  - "classpath: prefix in TlsConfiguration cert paths enables test resources without filesystem dependency"
  - "pom.xml reuseForks=true: all test classes run in single JVM fork to prevent OOM from multiple Spring Boot contexts (each has RocksDB + Netty; separate forks cause exit code 143)"
  - "Test CA is self-signed (CN=TBMQ Test CA), server cert and client cert both signed by test CA — validates full chain of trust in mTLS tests"

patterns-established:
  - "TLS bootstrap: separate SmartLifecycle bean at phase=1, isAutoStartup() gated on tlsConfig.isEnabled()"
  - "PEM loading: classpath: prefix resolved via ClassLoader.getResourceAsStream(), filesystem via FileInputStream"
  - "mTLS auth: SslHandler.engine().getSession().getPeerCertificates() used in auth service to extract CN"

requirements-completed: [TRAN-02, AUTH-02]

# Metrics
duration: 30min
completed: 2026-04-09
---

# Phase 4 Plan 3: TLS/mTLS Transport Security Summary

**Conditional TLS listener on port 8883 with BouncyCastle PEM loading, mTLS X.509 client cert authentication, and test PKI infrastructure — completes Phase 4 security stack**

## Performance

- **Duration:** ~30 min (continuation execution after prior agent committed Task 1)
- **Started:** 2026-04-09T07:10:00Z
- **Completed:** 2026-04-09T07:38:27Z
- **Tasks:** 2 (Task 1 committed by prior agent; Task 2 completed and committed in this session)
- **Files modified:** 16

## Accomplishments
- TLS listener infrastructure: PemSslCredentials (BouncyCastle, all key formats), AbstractSslCredentials, SslUtil.parseCommonName(), TlsConfiguration, MqttSslHandlerProvider, MqttSslChannelInitializer, MqttSslServerBootstrap (SmartLifecycle phase=1)
- Test PKI: CA cert, server cert (CN=localhost, SAN=127.0.0.1), client cert (CN=test-client), all CA-signed with 10-year validity
- MqttTlsIntegrationTest: 3 tests — TLS connect with trusted CA, plain TCP coexistence, untrusted cert handshake failure
- MqttMtlsIntegrationTest: 3 tests — mTLS connect with valid client cert, missing cert rejection, pub/sub over mTLS
- Full test suite: 113 tests pass, 3 skipped (@Disabled for future phases), 0 failures

## Task Commits

Each task was committed atomically:

1. **Task 1: TLS infrastructure — PEM loading, SslHandler provider, TLS bootstrap, channel initializer** - `08d191305` (feat)
2. **Task 2: Test PKI certificates + TLS and mTLS integration tests** - `91e2270e0` (feat)

**Plan metadata:** TBD (docs: complete plan — added after summary)

## Files Created/Modified
- `lightweight/src/main/java/.../ssl/SslCredentials.java` - SSL credentials interface (KeyManagerFactory, TrustManagerFactory)
- `lightweight/src/main/java/.../ssl/AbstractSslCredentials.java` - Base class: KeyStore initialization, KMF/TMF creation
- `lightweight/src/main/java/.../ssl/PemSslCredentials.java` - PEM file loader via BouncyCastle; classpath: and filesystem support
- `lightweight/src/main/java/.../ssl/SslUtil.java` - parseCommonName() via JcaX509CertificateHolder for mTLS CN extraction
- `lightweight/src/main/java/.../config/TlsConfiguration.java` - @Value properties: enabled, port, cert-path, key-path, client-auth, trust-cert-path
- `lightweight/src/main/java/.../server/tls/MqttSslHandlerProvider.java` - SslContext cached at @PostConstruct; SslHandler factory
- `lightweight/src/main/java/.../server/tls/MqttSslChannelInitializer.java` - SSL handler first in Netty pipeline
- `lightweight/src/main/java/.../server/tls/MqttSslServerBootstrap.java` - SmartLifecycle phase=1; isAutoStartup() gated on config
- `lightweight/src/test/resources/tls/ca.pem` - Test CA certificate (CN=TBMQ Test CA, self-signed)
- `lightweight/src/test/resources/tls/server.pem` - Test server certificate (CN=localhost, SAN=127.0.0.1)
- `lightweight/src/test/resources/tls/server-key.pem` - Test server private key (RSA 2048)
- `lightweight/src/test/resources/tls/client.pem` - Test client certificate (CN=test-client)
- `lightweight/src/test/resources/tls/client-key.pem` - Test client private key (RSA 2048)
- `lightweight/src/test/java/.../MqttTlsIntegrationTest.java` - TRAN-02: TLS connection tests
- `lightweight/src/test/java/.../MqttMtlsIntegrationTest.java` - AUTH-02: mTLS X.509 client cert auth tests
- `lightweight/pom.xml` - Added reuseForks=true to prevent OOM in test JVM

## Decisions Made
- **SmartLifecycle phase=1**: TLS bootstrap must start after TCP bootstrap (phase=0) to ensure actor system and session handlers are ready before accepting encrypted connections
- **SslContext cached at @PostConstruct**: PEM parsing is expensive; build SslContext once and reuse for all connections
- **classpath: prefix support**: Essential for tests where certs live in src/test/resources, not on filesystem at runtime; implemented in PemSslCredentials.openResource()
- **reuseForks=true in Surefire**: Without this, Maven Surefire creates a separate JVM fork per test class. Each fork loads a full Spring Boot context (RocksDB + Netty), and 15+ such forks cause OOM/SIGKILL (exit code 143). Single fork shares the JVM heap across all test contexts.
- **Test CA trust chain**: Server cert is signed by the test CA (not self-signed directly), which validates that the full certificate chain verification works correctly in both TLS and mTLS scenarios

## Deviations from Plan

None — plan executed exactly as written. Task 1 was committed by a prior agent execution; Task 2 was completed and committed in this session. All acceptance criteria met.

## Issues Encountered

The previous agent execution committed Task 1 (`08d191305`) but crashed before committing Task 2. On continuation, both TLS and mTLS tests already passed — the certificates and test code were correct. This session verified the tests, committed Task 2, and completed the documentation.

## User Setup Required

None — no external service configuration required. TLS is disabled by default (`tbmq.tls.enabled=false`). To enable TLS in production, set:
```
TBMQ_TLS_ENABLED=true
TBMQ_TLS_CERT_PATH=/path/to/server.pem
TBMQ_TLS_KEY_PATH=/path/to/server-key.pem
# For mTLS:
TBMQ_TLS_CLIENT_AUTH=REQUIRED
TBMQ_TLS_TRUST_CERT_PATH=/path/to/ca.pem
```

## Next Phase Readiness
- Phase 4 (security) is complete: credentials, ACL, auth pipeline, TLS, and mTLS all implemented and tested
- Phase 5 (WebSocket) can extend the channel initializer pattern established here (MqttSslChannelInitializer shows the pattern)
- Phase 6 (MQTT 5.0) can use the auth infrastructure as-is; SCRAM enhanced auth is the only security addition needed
- Docker: TLS cert paths need to be documented in docker-compose and environment variable documentation

---
*Phase: 04-security*
*Completed: 2026-04-09*
