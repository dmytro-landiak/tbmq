# Codebase Concerns

**Analysis Date:** 2026-03-26

## Tech Debt

**Deprecated `PROCESSED_BYTES` constant not yet removed:**
- Issue: `BrokerConstants.PROCESSED_BYTES` is marked with a TODO to delete completely (with an upgrade script to remove data from PostgreSQL), but it remains referenced in `HISTORICAL_KEYS` list.
- Files: `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/BrokerConstants.java` (line 40-45)
- Impact: Dead metric key pollutes historical data schema and creates confusion about what is tracked.
- Fix approach: Create a database upgrade script to remove `processedBytes` rows from the timeseries table, then remove the constant and all references.

**Dual Redis client libraries (Jedis + Lettuce):**
- Issue: The codebase uses both Jedis and Lettuce Redis clients simultaneously. A TODO explicitly states: "replace jedis from TBMQ implementation and use only lettuce."
- Files: `common/cache/src/main/java/org/thingsboard/mqtt/broker/cache/JedisClusterTopologyRefresher.java` (line 34), `common/cache/src/main/java/org/thingsboard/mqtt/broker/cache/TBRedisClusterConfiguration.java`, `common/cache/src/main/java/org/thingsboard/mqtt/broker/cache/TBRedisSentinelConfiguration.java`, `common/cache/src/main/java/org/thingsboard/mqtt/broker/cache/TBRedisStandaloneConfiguration.java`
- Impact: Two Redis client libraries increase dependency surface, memory footprint, and configuration complexity. Jedis is used for cluster topology refresh and Bucket4j rate limiting; Lettuce is used for async operations in `DeviceMsgServiceImpl`.
- Fix approach: Migrate all Jedis usage to Lettuce, remove Jedis dependency from `pom.xml`, and update Bucket4j integration to use Lettuce backend.

**JWT library migration pending:**
- Issue: `JwtTokenFactory` has a TODO: "switch to Nimbus JOSE + JWT". The current implementation uses `io.jsonwebtoken` (jjwt). Nimbus JOSE + JWT dependency (`nimbus-jose-jwt` v10.3) is already declared in `pom.xml`, suggesting partial migration.
- Files: `application/src/main/java/org/thingsboard/mqtt/broker/service/security/model/token/JwtTokenFactory.java` (line 49)
- Impact: Using the deprecated `SignatureException` from jjwt (line 25). Two JWT libraries increase dependency surface.
- Fix approach: Replace jjwt usage in `JwtTokenFactory` with Nimbus JOSE + JWT, then remove jjwt dependency.

**Deprecated synchronous Kafka admin methods:**
- Issue: Three methods in `TbQueueAdmin` interface are marked `@Deprecated(forRemoval = true, since = "2.3")`: `getClusterInfo()`, `getTopics(PageLink)`, and `getConsumerGroups(PageLink)`. Async replacements exist.
- Files: `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/TbQueueAdmin.java` (lines 49-61), `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/kafka/TbKafkaAdmin.java`
- Impact: Synchronous methods block threads during Kafka admin operations.
- Fix approach: Migrate all callers to async variants, then remove deprecated methods.

**Stale data update code not cleaned between releases:**
- Issue: `DefaultDataUpdateService.updateData()` has a TODO: "should be cleaned after each release". Similarly, `ThingsboardMqttBrokerInstallService` has TODOs to update supported versions and cleanup update code.
- Files: `application/src/main/java/org/thingsboard/mqtt/broker/service/install/update/DefaultDataUpdateService.java` (line 50), `application/src/main/java/org/thingsboard/mqtt/broker/install/ThingsboardMqttBrokerInstallService.java` (lines 52, 63)
- Impact: Accumulation of old migration code increases install service complexity over time.
- Fix approach: Establish a release checklist that includes cleaning up completed data update logic.

**`ThingsboardErrorResponseHandler` boilerplate:**
- Issue: TODO to refactor the class to use a centralized response-writing method instead of repeated `JacksonUtil.writeValue(response.getWriter(), ...)` boilerplate.
- Files: `application/src/main/java/org/thingsboard/mqtt/broker/exception/ThingsboardErrorResponseHandler.java` (line 239)
- Impact: Code duplication across error handling paths.
- Fix approach: Extract response writing into the existing `writeResponse` method and use it throughout the class.

**Forked Swagger dependency:**
- Issue: `springdoc-swagger.version` is set to `2.8.8TB` — a custom/forked build. This ties the project to an internally maintained fork.
- Files: `pom.xml` (line 73)
- Impact: Requires maintaining a fork of springdoc. Harder to upgrade to new upstream versions.
- Fix approach: Evaluate whether customizations can be contributed upstream or applied as configuration, then migrate to standard springdoc releases.

## Known Bugs

**Incorrect `isClientIdGenerated` flag when creating actors from cluster messages:**
- Symptoms: When `processSubscriptionsChanged` or `processSessionClusterManagementMsg` creates a new actor, it always passes `true` for `isClientIdGenerated`, which may be incorrect.
- Files: `application/src/main/java/org/thingsboard/mqtt/broker/session/ClientMqttActorManagerImpl.java` (lines 165-176)
- Trigger: Client disconnects from one node and a subscription change or cluster management message arrives at a node that doesn't have the actor yet.
- Workaround: None documented. The TODO says "get ClientInfo and check if clientId is generated."

**Race condition in cross-node client session state:**
- Symptoms: If a client disconnects from Node A and connects to Node B, the ClientSession changes may not be delivered to Node B yet.
- Files: `application/src/main/java/org/thingsboard/mqtt/broker/actors/client/service/session/ClientSessionServiceImpl.java` (line 85)
- Trigger: Rapid disconnect/reconnect across different cluster nodes.
- Workaround: The TODO proposes two solutions: (1) ask other nodes before connecting client, or (2) force wait if client was previously connected to another node. Neither is implemented.

**DUP flag may not be set correctly after Device Actor restart:**
- Symptoms: The DUP flag on re-delivered messages may not be correctly set if the Device Actor is dropped and recreated.
- Files: `application/src/main/java/org/thingsboard/mqtt/broker/actors/device/PersistedDeviceActorMessageProcessor.java` (line 184)
- Trigger: Device Actor is stopped (e.g., due to inactivity) and later recreated when the device reconnects.
- Workaround: None. The in-memory `inFlightPacketIds` set is lost when the actor is dropped.

## Security Considerations

**CVE dependency overrides in pom.xml:**
- Risk: Four dependencies are pinned to specific versions to patch known CVEs: Tomcat (CVE-2025-55752, CVE-2025-48989, CVE-2025-66614), Jackson (GHSA-72hv-8253-57qq), Logback (CVE-2026-1225), Commons Lang3 (CVE-2025-48924). These overrides are pending upstream Spring Boot fixes.
- Files: `pom.xml` (lines 42-48)
- Current mitigation: Version pinning addresses the CVEs. TODOs mark each for removal when Spring Boot catches up.
- Recommendations: Track Spring Boot release notes to remove pinned overrides promptly. Set up automated dependency vulnerability scanning.

**Credential search via LIKE on JSON text:**
- Risk: `MqttClientCredentialsRepository.findAllV2()` searches credentials by performing `LIKE` queries on the serialized JSON `credentialsValue` column. The query comment acknowledges "Not very accurate search because of `%param%`."
- Files: `dao/src/main/java/org/thingsboard/mqtt/broker/dao/client/MqttClientCredentialsRepository.java` (lines 45-80)
- Current mitigation: The query structure attempts to scope LIKE searches to specific JSON fields, but substring matching can produce false positives.
- Recommendations: Migrate `credentialsValue` to a JSONB column with GIN indexes for accurate, performant queries (as suggested by the existing TODO).

**Broad exception catching pattern:**
- Risk: Over 40 `catch (Exception e)` blocks in application source code. Some swallow exceptions silently (e.g., `HttpAuthClient.java` line 134: `catch (Exception ignored)`).
- Files: Multiple files across `application/src/main/java/`, notably `application/src/main/java/org/thingsboard/mqtt/broker/actors/client/ClientActor.java` (8 broad catch blocks), `application/src/main/java/org/thingsboard/mqtt/broker/controller/BaseController.java`
- Current mitigation: Most broad catches log the exception.
- Recommendations: Replace broad `Exception` catches with specific exception types where possible. Audit `ignored` catches to ensure they are intentional and documented.

## Performance Bottlenecks

**Blocking `.toCompletableFuture().get()` calls in actor processing:**
- Problem: `PersistedDeviceActorMessageProcessor.processSharedSubscriptions()` makes multiple blocking `.toCompletableFuture().get()` calls to Redis-backed `DeviceMsgService` within an actor thread. This blocks the actor thread pool while waiting for Redis I/O.
- Files: `application/src/main/java/org/thingsboard/mqtt/broker/actors/device/PersistedDeviceActorMessageProcessor.java` (lines 118-151). The method is annotated `@SneakyThrows` and calls `.get()` at lines 126, 140, 148, 150.
- Cause: Redis operations are async (`CompletionStage`-based) in `DeviceMsgServiceImpl`, but the actor code blocks on them synchronously. The existing TODO (line 118) explicitly calls this out.
- Improvement path: Refactor to use async composition (`.thenCompose()` / `.thenAccept()`) and send results back to the actor mailbox asynchronously instead of blocking.

**Credential search using LIKE on text column:**
- Problem: `MqttClientCredentialsRepository.findAllV2()` performs full-text `LIKE` searches with leading wildcards (`%param%`) on the `credentialsValue` text column, which cannot use standard B-tree indexes.
- Files: `dao/src/main/java/org/thingsboard/mqtt/broker/dao/client/MqttClientCredentialsRepository.java` (lines 40-80)
- Cause: Credentials data is stored as serialized JSON text rather than as a JSONB column.
- Improvement path: Migrate to JSONB column type and add GIN indexes for targeted JSON field queries (as suggested by the existing TODO at line 45).

**`ProtoConverter` god class (866 lines):**
- Problem: All protobuf-to-domain and domain-to-protobuf conversions are in a single static utility class, making it a hotspot for contention during code changes and harder to test in isolation.
- Files: `application/src/main/java/org/thingsboard/mqtt/broker/adaptor/ProtoConverter.java` (866 lines)
- Cause: Single-class approach for all conversion logic.
- Improvement path: Split into domain-specific converters (e.g., `SessionInfoProtoConverter`, `SubscriptionProtoConverter`, `PublishMsgProtoConverter`).

**Thread.sleep in consumer error recovery loops:**
- Problem: 18 instances of `Thread.sleep(pollDuration)` in Kafka consumer error recovery paths. These block dedicated consumer threads during error conditions.
- Files: `application/src/main/java/org/thingsboard/mqtt/broker/service/processing/PublishMsgConsumerServiceImpl.java` (line 148), `application/src/main/java/org/thingsboard/mqtt/broker/service/mqtt/persistence/application/ApplicationPersistenceProcessorImpl.java` (line 913), and 16 other consumer classes.
- Cause: Simple retry pattern using thread sleep after Kafka consumer failures.
- Improvement path: Consider exponential backoff or scheduled retry instead of blocking the thread.

## Fragile Areas

**`ApplicationPersistenceProcessorImpl` — highest complexity file (922 lines):**
- Files: `application/src/main/java/org/thingsboard/mqtt/broker/service/mqtt/persistence/application/ApplicationPersistenceProcessorImpl.java`
- Why fragile: Contains 7 `ConcurrentHashMap` fields managing parallel state for main and shared subscription processing. Manages multiple thread pools (persisted message consumers + shared subscription consumers). Has inconsistent logic between application and device persistence (TODO at line 403: "make consistent with logic for DEVICES").
- Safe modification: Changes require careful understanding of the concurrent state maps and how they interact with Kafka consumer lifecycle.
- Test coverage: `ApplicationPersistenceProcessorImplTest.java` exists (496 lines) but may not cover all concurrent edge cases.

**`ClientActor` message dispatch (414 lines):**
- Files: `application/src/main/java/org/thingsboard/mqtt/broker/actors/client/ClientActor.java`
- Why fragile: Large switch-based message dispatch with 8 broad `catch (Exception e)` blocks. Session state management via `SessionState` enum transitions. Backpressure handling via message queuing with `FullMsgQueueException`.
- Safe modification: Understand the `SessionState` state machine before modifying message handling. Test all message type combinations.
- Test coverage: Tests exist via `ActorProcessorImplTest.java` (620 lines) and handler-specific tests.

**Redis Lua scripts with hardcoded SHA hashes:**
- Files: `dao/src/main/java/org/thingsboard/mqtt/broker/dao/messages/DeviceMsgServiceImpl.java` (lines 43-47)
- Why fragile: Five Redis Lua scripts have their SHA1 hashes hardcoded as string constants. If scripts are modified, the corresponding SHA must be recalculated. A mismatch causes `RedisNoScriptException` at runtime, which is handled by a fallback to `EVAL` but with performance penalty.
- Safe modification: Always recalculate SHA1 when modifying any Lua script. The fallback mechanism (`exceptionallyCompose` with `RedisNoScriptException` check) provides resilience but adds latency.
- Test coverage: Lua scripts are tested indirectly through integration tests.

**`SessionClusterManagerImpl` — cross-node session coordination (547 lines):**
- Files: `application/src/main/java/org/thingsboard/mqtt/broker/actors/client/service/session/SessionClusterManagerImpl.java`
- Why fragile: Handles the critical path of client connection requests across cluster nodes. Uses request-response over Kafka with timeouts. The `isRequestTimedOut` check (line 119) can silently drop connection requests that arrive after timeout.
- Safe modification: Any changes to connection request/response flow must be tested across multiple broker nodes.
- Test coverage: `SessionClusterManagerImplTest.java` (482 lines) provides coverage but may not test all cluster timing scenarios.

## Scaling Limits

**In-memory subscription trie:**
- Current capacity: All active subscriptions held in `ConcurrentMapSubscriptionTrie` in memory.
- Limit: Memory-bound. Each subscription adds a trie node with topic levels as keys. The `clearEmptyNodes()` method requires a write lock on the entire trie, temporarily blocking all subscription lookups.
- Scaling path: The trie uses `ReadWriteLock` for concurrent access. Performance degrades under high churn of subscriptions. Consider sharding the trie by topic prefix or moving to an off-heap data structure for very high subscription counts.

**In-memory client session map:**
- Current capacity: `ClientSessionServiceImpl` holds all `ClientSessionInfo` objects in a `ConcurrentHashMap` (`clientSessionMap`).
- Limit: Memory-bound per broker node. Each node must hold the full view of all client sessions across the cluster (loaded from Kafka compact topic at startup).
- Scaling path: Already uses Kafka for persistence and cross-node sync. Memory consumption scales linearly with total connected clients across the cluster.

**Per-client Kafka consumer for application-persisted messages:**
- Current capacity: `ApplicationPersistenceProcessorImpl` creates a dedicated Kafka consumer per persistent APPLICATION client, plus additional consumers for shared subscriptions.
- Limit: Kafka has a practical limit on the number of consumers per consumer group. With unbounded cached thread pools (`initCachedExecutorService`), many application clients can exhaust thread resources.
- Scaling path: Consider consumer pooling or batch-based consumption for application clients.

## Dependencies at Risk

**Protobuf 3.x end-of-life concerns:**
- Risk: `protobuf.version` is 3.25.5. Protobuf 3.x is in maintenance mode; Protobuf 4.x (Edition 2023+) is the active line.
- Impact: Eventually lose security patches and new features.
- Migration plan: Migrate to Protobuf 4.x, which requires updating generated code and potentially build plugins.

**Forked springdoc-swagger (`2.8.8TB`):**
- Risk: Custom fork creates maintenance burden. Must be rebuilt for each upstream springdoc release.
- Impact: Blocks adoption of new springdoc features and security fixes.
- Migration plan: Evaluate whether fork customizations can be contributed upstream or achieved via standard configuration.

**Dual Redis client overhead (Jedis 5.1.5 + Lettuce 6.5.1):**
- Risk: Maintaining two Redis client libraries doubles the surface area for Redis-related bugs and configuration.
- Impact: Configuration confusion, increased JAR size, potential classpath conflicts.
- Migration plan: Complete migration to Lettuce as noted in the existing TODO.

## Missing Critical Features

**Integration retry strategy:**
- Problem: `IntegrationUplinkConsumer` has two TODOs (lines 92, 127) noting "improve the retry strategy." Currently, failed messages are logged and skipped — no dead-letter queue, no retry, no exponential backoff.
- Blocks: Reliable integration message delivery when downstream systems are temporarily unavailable.

**Application/Device persistence consistency:**
- Problem: TODO at `ApplicationPersistenceProcessorImpl.java` line 403 notes message ID sequence handling should be "consistent with logic for DEVICES" but is not.
- Blocks: Unified behavior guarantees for persistent session message delivery across client types.

**Kafka message reprocessing guard:**
- Problem: TODO at `PublishMsgConsumerServiceImpl.java` line 84 notes "all consumed messages can be processed multiple times (if kafka is disconnected while msgs are processing)." No idempotency guard exists.
- Blocks: Exactly-once message processing guarantees. Currently at-least-once semantics with potential duplicates.

## Test Coverage Gaps

**Common module under-tested:**
- What's not tested: Only 15 test files for 389 source files in the `common/` module.
- Files: `common/` (all submodules: `actor/`, `cache/`, `data/`, `queue/`, `stats/`, `util/`)
- Risk: Core shared utilities (JacksonUtil, ConcurrentHashMap-based data structures, cache operations) lack unit test coverage. Bugs in common code affect all modules.
- Priority: High — common code is the foundation for application and dao modules.

**Integration module minimally tested:**
- What's not tested: Only 7 test files for 41 source files in `integration/`.
- Files: `integration/executor/src/main/java/`
- Risk: Integration executor service logic (API service, connectivity, statistics) lacks coverage.
- Priority: Medium — integration is a newer module that will grow in importance.

**DAO layer test ratio:**
- What's not tested: 24 test files for 142 source files in `dao/`. Redis Lua scripts in `DeviceMsgServiceImpl` are only tested indirectly.
- Files: `dao/src/main/java/org/thingsboard/mqtt/broker/dao/messages/DeviceMsgServiceImpl.java` (5 embedded Lua scripts)
- Risk: Complex Redis Lua scripts that handle packet ID tracking, message trimming, and TTL could have edge case bugs not caught by higher-level tests.
- Priority: High — data layer correctness is critical for message persistence.

**Application test ratio:**
- What's not tested: 161 test files for 691 source files in `application/`. ~23% file coverage ratio.
- Files: Key untested areas likely include some consumer service implementations, stats services, and edge cases in the actor framework.
- Risk: Many consumer services follow similar patterns (poll loop + processing), but not all have dedicated unit tests.
- Priority: Medium — the core message processing path appears well-tested, but auxiliary services may have gaps.

## Configuration Complexity

**1,274-line YAML configuration file:**
- Issue: `application/src/main/resources/thingsboard-mqtt-broker.yml` contains ~694 configuration entries covering server, listeners, Kafka, security, Redis, stats, TTL, and more. All in a single file.
- Files: `application/src/main/resources/thingsboard-mqtt-broker.yml`
- Impact: Difficult to navigate. Easy to misconfigure. New developers must understand the full configuration surface.
- Recommendation: Configuration is well-documented with comments and uses environment variable overrides consistently (every value has a `${ENV_VAR:default}` pattern), which mitigates the complexity for deployment. Consider splitting into multiple profile-specific or domain-specific YAML files for development clarity.

**17 separate Kafka poll-interval configurations:**
- Issue: Each consumer type has its own `poll-interval` configuration, all defaulting to 100ms. This creates a large surface of tunable parameters that are rarely adjusted individually.
- Files: `application/src/main/resources/thingsboard-mqtt-broker.yml` (lines 246-360)
- Impact: Configuration bloat. Operators must understand which poll interval affects which consumer.
- Recommendation: Consider a global default poll interval with per-consumer overrides only where needed.

**Extensive thread pool configuration scattered across services:**
- Issue: At least 15 different `ThingsBoardExecutors.init*` calls create named thread pools, plus additional `Executors.newFixedThreadPool` and `Executors.newSingleThreadExecutor` calls. Each has its own thread count configuration via `@Value`.
- Files: Multiple files (see exploration output above for full list)
- Impact: Tuning thread pools requires understanding the interaction between ~20 different thread pools. Over- or under-provisioning any pool can cause resource contention.
- Recommendation: Document thread pool interactions and provide sizing guidelines based on expected load profiles.

---

*Concerns audit: 2026-03-26*
