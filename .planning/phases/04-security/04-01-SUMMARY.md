---
phase: 04-security
plan: 01
subsystem: security
tags: [auth, credentials, acl, spring-security, rocksdb, caffeine]
dependency_graph:
  requires: [01-01, 01-02, 01-03, 03-01]
  provides: [LightweightCredentialService, AuthorizationRuleService, LightweightAuthService, DefaultCredentialsInstaller, SecurityConfiguration]
  affects: [04-02, 04-03]
tech_stack:
  added: [spring-boot-starter-security, bcprov-jdk18on@1.79, bcpkix-jdk18on@1.79]
  patterns: [BCryptPasswordEncoder, Spring Cache @Cacheable/@CacheEvict, ApplicationReadyEvent listener, regex topic ACL]
key_files:
  created:
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/CredentialType.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/LightweightCredential.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/BasicMqttCredentials.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/SslMqttCredentials.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/PubSubAuthorizationRules.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/AuthRulePatterns.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/AuthResult.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/LightweightCredentialService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/DefaultLightweightCredentialService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/LightweightAuthService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/auth/DefaultLightweightAuthService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/acl/AuthorizationRuleService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/security/acl/DefaultAuthorizationRuleService.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/install/DefaultCredentialsInstaller.java
    - lightweight/src/main/java/org/thingsboard/mqtt/broker/lightweight/config/SecurityConfiguration.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/security/CredentialServiceTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/security/AclServiceTest.java
  modified:
    - lightweight/pom.xml
    - lightweight/src/main/resources/tbmq-lightweight.yml
decisions:
  - key: installer-event-listener
    description: DefaultCredentialsInstaller uses @EventListener(ApplicationReadyEvent) not @PostConstruct — RocksDB SmartLifecycle starts after bean initialization, so @PostConstruct fires before RocksDB is running
  - key: anonymous-empty-patterns
    description: Anonymous connections return AuthResult.success(emptyList()) — empty patterns list bypasses all ACL checks in DefaultAuthorizationRuleService.isPubAuthorized/isSubAuthorized
metrics:
  duration_minutes: 7
  completed_date: "2026-04-08"
  tasks_completed: 1
  files_created: 17
  files_modified: 2
---

# Phase 4 Plan 1: Security Service Layer Summary

**One-liner:** RocksDB-backed credential/ACL services with BCrypt password hashing, regex topic authorization, and default tbmq/tbmq credential installation via ApplicationReadyEvent.

## What Was Built

Complete security service layer for TBMQ Lightweight, providing the foundation for MQTT authentication and authorization:

- **Credential models** (`security/auth/` package): `CredentialType` enum (BASIC/SSL), `LightweightCredential` (the stored entity), `BasicMqttCredentials` (bcrypt password + ACL rules), `SslMqttCredentials` (CN-to-rules mapping), `PubSubAuthorizationRules` (regex pattern strings), `AuthRulePatterns` (compiled patterns), `AuthResult` (success/failure with factory methods)
- **LightweightCredentialService** + `DefaultLightweightCredentialService`: RocksDB CRUD on `CREDENTIALS` column family with `@Cacheable` (unless = null) and `@CacheEvict` using the existing Caffeine `credentials` cache
- **AuthorizationRuleService** + `DefaultAuthorizationRuleService`: Regex-based topic ACL enforcement with per-client publish cache, empty-patterns bypass for anonymous sessions, deny-by-default otherwise
- **LightweightAuthService** + `DefaultLightweightAuthService`: Unified auth service — SSL path first (extracts peer cert CN), then basic path (bcrypt match), then anonymous check
- **DefaultCredentialsInstaller**: Installs default `tbmq`/`tbmq` credential with allow-all ACL on first startup via `@EventListener(ApplicationReadyEvent.class)` with order=1
- **SecurityConfiguration**: Spring Security filter chain permitting all requests (including `/actuator/**`), `BCryptPasswordEncoder` bean, empty `InMemoryUserDetailsManager` to suppress random password log

## Task Commits

| Task | Description | Commit | Files |
|------|-------------|--------|-------|
| 1 | Security service layer with all classes and tests | e667c196f | 19 files |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed @PostConstruct timing with RocksDB SmartLifecycle**
- **Found during:** Task 1 (test failure: "RocksDB is not running")
- **Issue:** Plan specified `@PostConstruct` on `DefaultCredentialsInstaller.installDefaultCredentials()`. However, Spring's bean initialization phase (where `@PostConstruct` fires) occurs BEFORE `SmartLifecycle.start()` is invoked. RocksDB uses `SmartLifecycle` with `phase = Integer.MIN_VALUE`, so it starts during the lifecycle phase — after all beans are initialized. Calling the credential service from `@PostConstruct` hit the "RocksDB is not running" guard.
- **Fix:** Changed to `@EventListener(ApplicationReadyEvent.class)` with `@Order(1)`. This fires after all `SmartLifecycle` beans have started (including RocksDB). Consistent with the decision documented in STATE.md: "[Phase 01-foundation]: spring.config.name=tbmq-lightweight must be in @SpringBootTest properties"
- **Files modified:** `DefaultCredentialsInstaller.java`
- **Commit:** e667c196f

## Known Stubs

None. All credential service, ACL service, and auth service logic is wired to real RocksDB storage and returns real results. The empty patterns path for anonymous clients is intentional behavior (D-12), not a stub.

## Verification Results

- `mvn -pl lightweight compile -q` — SUCCESS
- `mvn -pl lightweight test -Dtest="CredentialServiceTest,AclServiceTest" -q` — 9/9 tests pass
- `mvn -pl lightweight test -q` — 98 tests pass (95 existing + 9 new), 3 pre-existing skips, 0 failures

## Self-Check: PASSED
