---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: executing
stopped_at: "Completed 04-03-PLAN.md: TLS/mTLS transport security"
last_updated: "2026-04-09T07:45:45.343Z"
last_activity: 2026-04-09
progress:
  total_phases: 7
  completed_phases: 4
  total_plans: 14
  completed_plans: 14
  percent: 100
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-08)

**Core value:** A fully functional MQTT broker that starts with a single `docker run` command and requires zero external infrastructure
**Current focus:** Phase 04 — security

## Current Position

Phase: 5
Plan: Not started
Status: Ready to execute
Last activity: 2026-04-09

Progress: [████████████████████] 3/3 plans (100%)

## Performance Metrics

**Velocity:**

- Total plans completed: 0
- Average duration: -
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

**Recent Trend:**

- Last 5 plans: -
- Trend: -

*Updated after each plan completion*
| Phase 01-foundation P01 | 6 | 2 tasks | 10 files |
| Phase 01-foundation P02 | 9 | 2 tasks | 10 files |
| Phase 01-foundation P03 | 4 | 1 tasks | 2 files |
| Phase 01-foundation P03 | 15 | 2 tasks | 2 files |
| Phase 02-core-protocol-mqtt-3-1-1 P02 | 2 | 2 tasks | 10 files |
| Phase 02-core-protocol-mqtt-3-1-1 P01 | 6 | 2 tasks | 26 files |
| Phase 02-core-protocol-mqtt-3-1-1 P03 | 6 | 3 tasks | 16 files |
| Phase 02-core-protocol-mqtt-3-1-1 P04 | 8 | 2 tasks | 19 files |
| Phase 02-core-protocol-mqtt-3-1-1 P05 | 13 | 2 tasks | 14 files |
| Phase 03-message-dispatch P01 | 3 | 2 tasks | 8 files |
| Phase 03-message-dispatch P02 | 9 | 2 tasks | 14 files |
| Phase 03-message-dispatch P03 | 7 | 2 tasks | 4 files |
| Phase 04-security P01 | 7 | 1 tasks | 19 files |
| Phase 04-security P02 | 14 | 2 tasks | 9 files |
| Phase 04-security P03 | 30 | 2 tasks | 16 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Foundation: Docker base image MUST be `eclipse-temurin:17-jre-jammy` — Alpine/musl is hard-blocked by RocksDB JNI incompatibility (ARM64 `UnsatisfiedLinkError`)
- Foundation: Pin Netty to 4.1.x explicitly — Netty 4.2 has breaking API changes; do not let dependency management upgrade it
- Foundation: Use RocksDB 9.7.4 (rocksdbjni) — v10.x is too fresh (Dec 2025); do not upgrade before R1 ships
- Dispatch: Start `MsgDispatcherService` with `LinkedBlockingQueue` behind `PublishMsgQueueFactory` interface; upgrade to LMAX Disruptor only if benchmarks show saturation
- [Phase 01-foundation]: Added @EnableAutoConfiguration to TbmqLightweightApplication — unlike parent TBMQ, lightweight standalone project requires explicit auto-config for MeterRegistry and other Spring Boot beans
- [Phase 01-foundation]: RocksDB SmartLifecycle running flag must be set before schema_version initialization in start() — internal startup logic bypasses public API guards that check the running state
- [Phase 01-foundation]: spring.config.name=tbmq-lightweight must be in @SpringBootTest properties — main() updateArguments() is not invoked by Spring Test framework
- [Phase 01-foundation]: management.prometheus.metrics.export.enabled=true must be explicit in yml — Spring Boot 3.5 defaults prometheus export to false
- [Phase 01-foundation]: CaffeineCacheMetrics.monitor() must use same tags as Spring Boot auto-config [cache, cache.manager, name] to avoid Prometheus tag collision warnings
- [Phase 01-foundation]: Use maven:3.9-eclipse-temurin-17 (not -jammy variant) for Docker build stage — the -jammy suffix tag does not exist for this Maven+JDK combination; build stage OS is irrelevant since only the JAR is copied to runtime
- [Phase 01-foundation]: Docker: Use maven:3.9-eclipse-temurin-17 (not -jammy) for build stage; runtime MUST be eclipse-temurin:17-jre-jammy (glibc) — Alpine/musl hard-blocked by RocksDB JNI on ARM64
- [Phase 02-core-protocol-mqtt-3-1-1]: Test scaffold: @Disabled at method level (not class) so Surefire counts pending tests; each annotation includes enabling plan number for traceability
- [Phase 02-core-protocol-mqtt-3-1-1]: TbTypeActorId uses plain String type (not ActorType enum) — lightweight has no common/data module dependency
- [Phase 02-core-protocol-mqtt-3-1-1]: ActorSystemConfiguration uses @Value for actor properties to avoid circular bean dependency with DefaultTbActorSystem @Bean
- [Phase 02-core-protocol-mqtt-3-1-1]: MsgType simplified to 13 Phase 2 types only — no DEVICE_* or INTEGRATION_* entries needed in single-node mode
- [Phase 02-core-protocol-mqtt-3-1-1]: SessionCloseMsg reuses DISCONNECT_MSG type to avoid MsgType enum additions; actor distinguishes by instanceof check
- [Phase 02-core-protocol-mqtt-3-1-1]: Raw socket buildMqttConnectPacket() helper used for keep-alive expiry test — Paho auto-sends PINGREQ and cannot simulate idle clients
- [Phase 02-core-protocol-mqtt-3-1-1]: PingMsg is singleton (INSTANCE pattern) — PINGREQ carries no data per spec, eliminating per-message allocation
- [Phase 02-core-protocol-mqtt-3-1-1]: DefaultSubscriptionRegistry uses ConcurrentHashMap.compute() for atomic re-subscribe QoS updates; inboundQos2 stores PublishMsg (not Boolean) for QoS 2 delivery on PUBREL; wildcard subscriptions stored but not matched in Phase 2 (exact-match only D-06)
- [Phase 02-core-protocol-mqtt-3-1-1]: LWT keyed by session UUID (not clientId) for correct takeover isolation: old session and new session have different UUIDs, so their LWT entries don't interfere during client takeover
- [Phase 02-core-protocol-mqtt-3-1-1]: Raw socket MQTT connect packet used for LWT ungraceful disconnect tests — Paho disconnectForcibly causes 30-70s delays due to internal keepalive timer; raw socket close is immediate and deterministic
- [Phase 03-message-dispatch]: ConcurrentHashMap.newKeySet() used over Guava Sets.newConcurrentHashSet() — avoids Guava dependency in lightweight module
- [Phase 03-message-dispatch]: throws Exception in clearEmptyNodes() interface methods — avoids importing custom TBMQ exception hierarchy in lightweight
- [Phase 03-message-dispatch]: notStartingWith$() guards against empty topic string in ConcurrentMapSubscriptionTrie — fixes latent TBMQ upstream bug
- [Phase 03-message-dispatch]: queue.offer() enforced over queue.put() — non-blocking dispatch per D-04; drop and count when full
- [Phase 03-message-dispatch]: @EqualsAndHashCode(of=clientId) on Subscription — trie addOrReplace requires clientId-only equality for correct re-subscribe QoS update
- [Phase 03-message-dispatch]: Per-client topic filter index in DefaultSubscriptionRegistry — avoids O(trie_size) full scan on disconnect; removeAllSubscriptions is O(client_subscriptions)
- [Phase 03-message-dispatch]: testRetainedMsg_sysTopicNotDeliveredOnWildcard uses isolated namespace (sys-test/#) for test isolation in shared Spring context
- [Phase 03-message-dispatch]: start()+stop() in unit test setUp() initializes queue/counter fields without running consumer threads for deterministic testing
- [Phase 04-security]: DefaultCredentialsInstaller uses @EventListener(ApplicationReadyEvent) not @PostConstruct — RocksDB SmartLifecycle starts after bean initialization
- [Phase 04-security]: Anonymous connections return AuthResult.success(emptyList()) — empty patterns list bypasses all ACL checks in isPubAuthorized/isSubAuthorized
- [Phase 04-security]: Auth check inserted BEFORE session registration in processConnect() (per D-04) — avoids registering sessions that will be immediately rejected
- [Phase 04-security]: PUBLISH ACL denial sends QoS acks before dropping message — avoids protocol violation while enforcing ACL silently
- [Phase 04-security]: defaultConnectOptions() in AbstractMqttIntegrationTest includes tbmq/tbmq credentials — required since auth is enforced by default
- [Phase 04-security]: SmartLifecycle phase=1 for TLS bootstrap so it starts after TCP bootstrap (phase=0)
- [Phase 04-security]: SslContext cached at @PostConstruct — avoids PEM re-parsing on every connection
- [Phase 04-security]: pom.xml reuseForks=true: all test classes run in single JVM fork to prevent OOM from multiple Spring Boot contexts (RocksDB + Netty per context; separate forks cause SIGKILL exit code 143)
- [Phase 04-security]: classpath: prefix in PEM paths enables test cert loading from src/test/resources without filesystem dependency

### Roadmap Evolution

None yet.

### Pending Todos

None yet.

### Blockers/Concerns

- RocksDB schema evolution: No migration framework defined. A `metadata` column family with a schema version key is recommended. Decide migration strategy before R2 schema changes.
- Performance targets: No explicit throughput floor defined. Recommended floor: 10,000 concurrent connections and 50,000 msg/sec sustained. Confirm before Phase 3 benchmarks.
- R2 forward-compatible storage layout: R1 RocksDB schema should be designed to accommodate future persistent session columns without migration pain. Address in Phase 1 column family design.

## Session Continuity

Last session: 2026-04-09T07:39:50.521Z
Stopped at: Completed 04-03-PLAN.md: TLS/mTLS transport security
Resume file: None
