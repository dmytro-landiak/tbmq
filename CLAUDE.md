<!-- GSD:project-start source:PROJECT.md -->
## Project

**TBMQ Lightweight**

TBMQ Lightweight is a stripped-down, single-node MQTT broker that eliminates all external infrastructure dependencies — no Kafka, no PostgreSQL, no Redis/Valkey. It ships as a single Docker image and covers the core MQTT protocol surface (3.1.1 and 5.0) with embedded storage and in-process messaging, targeting developers evaluating TBMQ, small-scale production deployments, and edge environments where operational simplicity matters more than horizontal scalability.

**Core Value:** A fully functional MQTT broker that starts with a single `docker run` command and requires zero external infrastructure — making TBMQ accessible for evaluation and small-scale production without the deployment complexity of the standard version.

### Constraints

- **Single node**: No clustering, no distributed coordination — simplifies architecture but limits scale
- **No persistence across restarts (R1)**: Sessions and retained messages are in-memory only; only credentials and ACLs survive restarts via RocksDB
- **JVM-based**: Must run on Java 17+; RocksDB JNI adds platform-specific native library requirements
- **Multi-arch Docker**: Must support both x86 and ARM architectures for edge deployments
- **Separate repository**: Preferred approach — keeps standard TBMQ clean, independent release cycle
<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

## Languages
- Java 17 - Backend (MQTT broker, DAO, queue, integration executor). Configured via `maven.compiler.source`/`target` in `pom.xml`.
- TypeScript ~5.5.4 - Frontend Angular UI in `ui-ngx/`.
- SQL (PostgreSQL dialect) - Database schema and migrations in `dao/src/main/resources/sql/`.
- Protocol Buffers - Inter-service messaging schemas in `common/queue/src/main/proto/queue.proto` and `common/queue/src/main/proto/integration.proto`.
- SCSS - UI component styles (configured in `ui-ngx/angular.json`).
## Runtime
- JDK 17 (OpenJDK) - Docker base image: `thingsboard/openjdk17:bookworm-slim` (see `msa/tbmq/docker/Dockerfile`, `msa/mqtt-broker/docker/Dockerfile`, `msa/integration/executor/docker/Dockerfile`).
- Node.js - Required for frontend build (`ui-ngx/`). Uses `--max_old_space_size=8048` for dev and `4096` for production builds.
- Maven - Backend build tool. Root `pom.xml` defines multi-module reactor build.
- npm - Frontend package management. Lockfile: `ui-ngx/package-lock.json` (check existence).
- Gradle - Used for native packaging (deb/rpm) via `gradle-maven-plugin` invoked from Maven.
## Frameworks
- Spring Boot 3.4.13 - Application framework. Defined in `pom.xml` as `spring-boot.version`. Starters used: `spring-boot-starter-web`, `spring-boot-starter-security`, `spring-boot-starter-websocket`, `spring-boot-starter-webflux`, `spring-boot-starter-data-jpa`, `spring-boot-starter-freemarker`, `spring-boot-starter-actuator`.
- Angular 19.0.6 - Frontend SPA framework. See `ui-ngx/package.json`.
- Netty (via `netty-all`, `netty-handler`, `netty-codec-mqtt`, `netty-transport-native-epoll`) - High-performance MQTT TCP/SSL/WS/WSS listeners. Core network I/O layer.
- JUnit 5 + JUnit Vintage Engine - Test execution. Surefire plugin version 3.2.5.
- Mockito 5.18.0 - Mocking framework.
- Testcontainers 1.20.6 - Integration tests with PostgreSQL and Kafka containers.
- WireMock Testcontainers 1.0-alpha-13 - HTTP integration testing.
- Awaitility - Async test assertions.
- DBUnit 2.7.3 + spring-test-dbunit 1.3.0 - Database test fixtures.
- Eclipse Paho MQTT Client 1.2.5 (v3 and v5) - MQTT client for integration tests.
- HiveMQ MQTT Client 1.3.3 - Alternative MQTT client for tests.
- ConcurrentUnit 0.4.6 - Concurrent test assertions.
- ESLint 9.17.0 - Frontend linting.
- Maven (multi-module) - Primary build system. See `pom.xml`.
- Maven Surefire Plugin 3.2.5 - Test execution.
- Protobuf Maven Plugin (`org.xolstice.maven.plugins:protobuf-maven-plugin`) - Protobuf code generation.
- Spring Boot Maven Plugin - Fat JAR packaging with embedded launch script.
- Gradle Maven Plugin (`org.thingsboard:gradle-maven-plugin`) - Deb/RPM package building.
- Angular CLI 19.0.7 - Frontend build tooling.
- Custom esbuild builder (`@angular-builders/custom-esbuild:18.0.0`) - Custom esbuild plugins for Angular build. Config: `ui-ngx/esbuild/tb-esbuild-plugins.ts`.
- TailwindCSS 3.4.15 - Utility-first CSS framework. Config: `ui-ngx/tailwind.config.js`.
- PostCSS 8.4.49 + Autoprefixer 10.4.20 - CSS post-processing.
## Key Dependencies
- `org.apache.kafka:kafka-clients` 3.9.1 - Apache Kafka client for all inter-broker and message queue communication.
- `io.netty:netty-codec-mqtt` (managed via Spring Boot BOM) - MQTT protocol codec for Netty pipeline.
- `com.google.protobuf:protobuf-java` 3.25.5 - Serialization for Kafka messages between services.
- `org.postgresql:postgresql` (managed via Spring Boot BOM) - PostgreSQL JDBC driver.
- `redis.clients:jedis` 5.1.5 - Redis/Valkey client (Jedis mode).
- `io.lettuce:lettuce-core` 6.5.1.RELEASE - Redis/Valkey client (Lettuce mode, preferred for reactive).
- `org.springframework.data:spring-data-redis` - Spring Data Redis abstraction.
- `com.bucket4j:bucket4j_jdk17-core` 8.13.0 - Token-bucket rate limiting (also `bucket4j_jdk17-jedis`, `bucket4j_jdk17-redis-common`).
- `io.jsonwebtoken:jjwt` 0.12.5 - JWT token generation/validation for REST API.
- `com.nimbusds:nimbus-jose-jwt` 10.3 - JWT MQTT auth provider support.
- `com.google.crypto.tink:tink` 1.11.0 - Google crypto library for JWT MQTT auth.
- `org.bouncycastle:bcprov-jdk18on` + `bcpkix-jdk18on` 1.79 - X.509 certificate handling for TLS/SSL MQTT auth.
- `org.passay:passay` 1.6.4 - Password validation rules.
- `org.owasp.antisamy:antisamy` 1.7.5 - HTML sanitization/XSS prevention.
- `org.springframework.boot:spring-boot-starter-security` - Spring Security for REST API auth.
- `org.projectlombok:lombok` 1.18.38 - Boilerplate code reduction (provided scope).
- `com.google.guava:guava` 33.1.0-jre - Utilities and collections.
- `org.apache.commons:commons-lang3` 3.18.0 - String/object utilities.
- `com.fasterxml.jackson` 2.18.6 - JSON serialization (managed via Jackson BOM).
- `com.github.oshi:oshi-core` 6.6.0 - System info (CPU/memory monitoring).
- `ch.qos.logback:logback-classic` 1.5.25 - Logging implementation.
- `com.sun.mail:jakarta.mail` 2.0.1 - Email sending.
- `@angular/material` 19.0.5 - UI component library.
- `@ngrx/store` 18.1.1 + `@ngrx/effects` 18.1.1 - State management.
- `rxjs` 7.8.1 - Reactive programming.
- `mqtt` 5.3.0 - MQTT.js client for WebSocket MQTT client in browser.
- `chart.js` 4.4.0 + plugins - Dashboard charting.
- `ace-builds` 1.36.2 - Code editor.
- `@ngx-translate/core` 15.0.0 - i18n support.
- `org.thingsboard:springdoc-openapi-starter-webmvc-ui` 2.8.8TB (ThingsBoard custom fork) - Swagger/OpenAPI UI.
- `io.swagger.core.v3:swagger-annotations-jakarta` 2.2.30 - API annotations.
## Configuration
- All configuration is externalized via environment variables with defaults in `application/src/main/resources/thingsboard-mqtt-broker.yml`.
- Pattern: `"${ENV_VAR_NAME:default_value}"` throughout the YAML.
- Docker env files exist at `docker/tbmq.env`, `docker/kafka.env`, `docker/tbmq-integration-executor.env`, `docker/cache-valkey.env`, `docker/cache-valkey-cluster.env`, `docker/cache-valkey-sentinel.env`.
- i18n messages: `application/src/main/resources/i18n/messages.properties`.
- `pom.xml` - Root Maven POM with all version properties and dependency management.
- `application/pom.xml` - Main broker application.
- `integration/executor/pom.xml` - Integration executor application.
- `ui-ngx/angular.json` - Angular build configuration.
- `ui-ngx/tsconfig.json` - TypeScript compiler configuration (target: ES2022, module: es2020).
## Maven Modules
## Platform Requirements
- JDK 17+
- Maven 3.6+
- Node.js (for `ui-ngx` build)
- Docker (for integration tests via Testcontainers)
- PostgreSQL 17 (or use Testcontainers)
- Apache Kafka 4.0.0 (or use Testcontainers)
- Valkey/Redis 8.0 (for caching/rate limiting)
- Docker with `thingsboard/openjdk17:bookworm-slim` base image
- PostgreSQL 17
- Apache Kafka 4.0.0 (KRaft mode, no ZooKeeper)
- Valkey 8.0 (standalone, sentinel, or cluster)
- HAProxy (for load balancing in cluster mode) - image `thingsboard/haproxy-certbot:2.2.33-alpine`
- Ports: 1883 (MQTT TCP), 8083 (HTTP/REST API), 8084 (MQTT WebSocket), 8883 (MQTTS), 8085 (MQTT WSS)
- Deb packages built via Gradle packaging plugin
- Docker images: `thingsboard/tbmq`, `thingsboard/tbmq-integration-executor`
- Kubernetes: Helm charts and manifests for AWS, GCP, Azure, Minikube in `k8s/`
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

## License Header
## Naming Patterns
- Root package: `org.thingsboard.mqtt.broker`
- Use lowercase, dot-separated segments that mirror the module/layer purpose
- Examples: `service.mqtt.retain`, `actors.client.service.handlers`, `dao.model`
- PascalCase for all classes
- Interfaces: plain descriptive name (e.g., `RetainedMsgProcessor`, `MqttClientCredentialsService`, `Dao`)
- Interface implementations in `application` module: prefix with `Default` (e.g., `DefaultTbMqttClientCredentialsService`, `DefaultMailService`, `DefaultTbAdminService`)
- Interface implementations in `dao` module: suffix with `Impl` (e.g., `MqttClientCredentialsServiceImpl`, `WebSocketConnectionServiceImpl`, `UserServiceImpl`)
- Entity classes (JPA): suffix with `Entity` (e.g., `UserEntity`, `MqttClientCredentialsEntity`, `IntegrationEntity`)
- Domain/Data classes: no suffix (e.g., `User`, `Integration`, `MqttClientCredentials`)
- DTOs: suffix with `Dto` (e.g., `RetainedMsgDto`, `ShortClientSessionInfoDto`, `AdminDto`)
- Controllers: suffix with `Controller` (e.g., `MqttClientCredentialsController`, `ClientSessionController`)
- Configuration: suffix with `Configuration` or `Properties` (e.g., `IncomingRateLimitsConfiguration`, `ClientsLimitProperties`)
- Constants: suffix with `Constants` (e.g., `BrokerConstants`, `ModelConstants`, `ControllerConstants`)
- Test suites: suffix with `TestSuite` (e.g., `DaoServiceTestSuite`, `IntegrationTestSuite`)
- Abstract base classes: prefix with `Abstract` (e.g., `AbstractDao`, `AbstractTbEntityService`, `AbstractPubSubIntegrationTest`)
- camelCase
- Service methods: verb-first (e.g., `saveCredentials`, `findById`, `deleteCredentials`, `getCredentialsById`)
- Check/validation methods: `check` prefix (e.g., `checkNotNull`, `checkUserId`, `checkIntegrationId`)
- Factory methods: `newInstance` pattern (e.g., `RetainedMsgDto.newInstance(retainedMsg)`)
- camelCase for instance/local variables
- `UPPER_SNAKE_CASE` for static final constants
- Constants for property names in `ModelConstants`: `ENTITY_NAME_PROPERTY_NAME_PROPERTY` pattern (e.g., `USER_EMAIL_PROPERTY`, `MQTT_CLIENT_CREDENTIALS_TYPE_PROPERTY`)
- Java files: PascalCase matching class name
- Config files: kebab-case YAML (`thingsboard-mqtt-broker.yml`)
- SQL files: kebab-case (`schema-entities.sql`, `drop-all-tables.sql`)
- Proto files: lowercase (`queue.proto`, `integration.proto`)
## Lombok Usage
- `@Slf4j` -- on nearly all service classes and controllers for logging
- `@Data` -- on DTOs, entities, configuration beans
- `@Getter` / `@Setter` -- on domain model classes (e.g., `User`, `BaseData`)
- `@RequiredArgsConstructor` -- on service implementations for constructor injection
- `@EqualsAndHashCode(callSuper = true)` -- on entity/domain subclasses
- `@Builder` -- on test data objects and some message types
- `@AllArgsConstructor` / `@NoArgsConstructor` -- on builder-compatible classes
## Spring Patterns
### Service Layer (application module)
### Controller Pattern
- All controllers extend `BaseController` at `application/src/main/java/org/thingsboard/mqtt/broker/controller/BaseController.java`
- Annotated with `@RestController`, `@RequiredArgsConstructor`, `@RequestMapping("/api")`
- All endpoints secured with `@PreAuthorize("hasAuthority('SYS_ADMIN')")`
- Use `createPageLink()` from `BaseController` for pagination
- Error handling done centrally via `@ExceptionHandler` in `BaseController`
- Exception mapping: `ThingsboardException` with `ThingsboardErrorCode` enum
### Dependency Injection
- Prefer `@RequiredArgsConstructor` with `private final` fields for constructor injection (in newer code)
- Older code uses `@Autowired` field injection (especially in `BaseController` and `AbstractTbEntityService`)
- For new code, use constructor injection via `@RequiredArgsConstructor`
### Configuration Pattern
- Located in `application/src/main/java/org/thingsboard/mqtt/broker/config/`
- Use `@Configuration` + `@ConfigurationProperties(prefix = "...")` + `@Data`
- Some config uses `@Value("${property.name}")` for individual properties (seen in `BaseController`, `BrokerHomePageConfig`)
- Main config: `application/src/main/resources/thingsboard-mqtt-broker.yml`
- All properties support environment variable overrides: `"${ENV_VAR:defaultValue}"`
- Pattern: `property_name: "${ENV_VAR_NAME:default_value}"`
## DAO / Data Access Layer
### Entity-Domain Mapping Pattern
- Extend `BaseData` (which extends `IdBased`)
- Use Lombok `@Getter`/`@Setter`
- UUID-based IDs
- Located at `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/`
- Extend `BaseSqlEntity<DomainClass>` at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/model/BaseSqlEntity.java`
- Implement `BaseEntity<DomainClass>` at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/model/BaseEntity.java`
- Provide constructor from domain class and `toData()` method for reverse mapping
- Use `ModelConstants` for column name references at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/model/ModelConstants.java`
- All column names use `snake_case` constants
### DAO Pattern
- Interface: `Dao<T>` at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/Dao.java` -- generic CRUD interface
- Abstract base: `AbstractDao<E extends BaseEntity<D>, D>` at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/AbstractDao.java`
- Concrete DAOs extend `AbstractDao` and provide `getEntityClass()` and `getCrudRepository()`
- Spring Data JPA repositories for actual database queries
- `DaoUtil` at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/DaoUtil.java` for entity-to-domain conversion helpers
### Validation
- `DataValidator<D extends BaseData>` at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/service/DataValidator.java` -- template method pattern for entity validation
- `Validator` at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/service/Validator.java` -- static utility methods (`validateId`, `validateString`, `validatePageLink`)
- `ConstraintValidator` at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/service/ConstraintValidator.java` -- Jakarta Bean Validation integration
- Custom validation annotations: `@NoXss`, `@Length` in `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/validation/`
## DTO Pattern
- Located in `application/src/main/java/org/thingsboard/mqtt/broker/dto/`
- Use Lombok `@Data`
- Often provide static factory methods: `newInstance(domainObj)`
- May include comparator factory methods: `getComparator(SortOrder)`
- Also used in `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/dto/` for cross-module DTOs
## Protobuf / gRPC
- Proto files at `common/queue/src/main/proto/queue.proto` and `common/queue/src/main/proto/integration.proto`
- Generated Java package: `org.thingsboard.mqtt.broker.gen.queue`
- Proto naming: PascalCase message names with `Proto` suffix (e.g., `PublishMsgProto`, `SessionInfoProto`, `ClientInfoProto`)
- Used for Kafka message serialization, not gRPC service calls
## Error Handling
- `ThingsboardException` (checked) at `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/exception/ThingsboardException.java` -- primary exception with `ThingsboardErrorCode`
- `DataValidationException` (unchecked) at `common/data/src/main/java/org/thingsboard/mqtt/broker/exception/DataValidationException.java` -- for validation failures
- `IncorrectParameterException` (unchecked) at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/exception/IncorrectParameterException.java` -- for invalid parameters
- `TbRateLimitsException` at `common/data/src/main/java/org/thingsboard/mqtt/broker/exception/TbRateLimitsException.java`
- `@ExceptionHandler` methods in `BaseController` catch and map all exceptions to `ThingsboardException`
- `ThingsboardErrorResponseHandler` at `application/src/main/java/org/thingsboard/mqtt/broker/exception/ThingsboardErrorResponseHandler.java` formats HTTP error responses
- Error codes defined in `ThingsboardErrorCode` enum: `GENERAL`, `AUTHENTICATION`, `BAD_REQUEST_PARAMS`, `ITEM_NOT_FOUND`, `DATABASE`
## Logging
- Use `@Slf4j` annotation on classes, then reference `log` field
- Use parameterized messages with `{}` placeholders (never string concatenation)
- Use `log.trace` for detailed debugging (protocol-level tracing)
- Use `log.debug` for operation-level debugging
- Use `log.info` for startup/lifecycle events
- Use `log.warn` for recoverable issues
- Use `log.error` for failures with exception parameter
## Import Organization
## Code Style
- 4-space indentation (Java)
- Opening brace on same line
- No explicit formatting tool configuration detected (no Checkstyle, no EditorConfig)
- Max line length is not formally enforced
- Grouped by entity/feature area in constants classes (`BrokerConstants`, `ModelConstants`)
- Private constructor on utility/constants classes to prevent instantiation
- `@AfterStartUp(order = N)` at `application/src/main/java/org/thingsboard/mqtt/broker/config/annotations/AfterStartUp.java` -- wraps `@EventListener(ApplicationReadyEvent.class)` + `@Order`
- `@DaoSqlTest` at `dao/src/test/java/org/thingsboard/mqtt/broker/dao/DaoSqlTest.java` -- composite annotation for DAO tests with SQL test properties
## Module Design
- Each module is a Maven artifact; cross-module dependencies declared in POM
- `dao` module exports a test-jar for reuse in `application` module tests
- `common/data` module provides shared domain model classes
- `common/queue` module provides protobuf definitions and Kafka abstractions
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

## Pattern Overview
- Spring Boot application with Netty-based MQTT protocol handling
- Actor model for per-client session management (one actor per MQTT client)
- Apache Kafka as the central message bus for publish/subscribe routing and inter-node communication
- PostgreSQL for persistent entity storage, Redis for caching
- Separate "Integration Executor" microservice for external integrations (MQTT, HTTP, Kafka)
- Protobuf serialization for all Kafka messages
## Layers
- Purpose: Accept MQTT connections over TCP, TLS, WebSocket, and WSS
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/server/`
- Contains: Server bootstraps, channel initializers, and the `MqttSessionHandler`
- Depends on: Netty, Actor system (via `ClientMqttActorManager`)
- Used by: MQTT clients connecting to the broker
- Key files:
- Purpose: Per-client concurrency isolation using the actor model; each MQTT client ID gets its own actor
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/actors/`
- Contains: Actor system, client actors, device actors, message types
- Depends on: Common actor framework (`common/actor/`), service layer
- Used by: Transport layer (via `ClientMqttActorManager`)
- Key files:
- Purpose: Core business logic for MQTT message processing, subscriptions, persistence, authentication
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/service/`
- Contains: MQTT services, subscription management, message processing, authentication, rate limiting
- Depends on: DAO layer, Queue layer, Cache layer
- Used by: Actor layer
- Key sub-packages:
- Purpose: Management REST API for admin UI operations
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/controller/`
- Contains: Spring MVC controllers for sessions, credentials, subscriptions, retained messages, etc.
- Depends on: Service layer, Security
- Used by: Admin UI (`ui-ngx/`)
- Key files:
- Purpose: Database access (PostgreSQL) via Spring Data JPA
- Location: `dao/src/main/java/org/thingsboard/mqtt/broker/dao/`
- Contains: JPA repositories, entity models, SQL services
- Depends on: PostgreSQL, Spring Data JPA
- Used by: Service layer
- Key sub-packages:
- Purpose: Kafka-based messaging for publish/subscribe routing and inter-node communication
- Location: `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/`
- Contains: Kafka producer/consumer templates, topic configuration, queue factories
- Depends on: Apache Kafka, Protobuf
- Used by: Service layer (processing, session events, downlink)
- Key files:
- Purpose: Shared data models, utilities, cache configuration, statistics
- Location: `common/`
- Contains: Data models, actor framework, cache (Redis), queue abstractions, stats, utilities
- Depends on: External libraries only
- Used by: All other layers
## Data Flow
- **In-memory subscription trie**: `ConcurrentMapSubscriptionTrie` - concurrent trie data structure for O(topic-depth) subscription matching
- **In-memory retained message trie**: `ConcurrentMapRetainMsgTrie` - stores retained messages for topic matching
- **Client session cache**: In-memory `ConcurrentHashMap` tracking which clients are connected to which broker node
- **Kafka compacted topics**: Used as the source of truth for client sessions, subscriptions, retained messages, and blocked clients. On startup, each node replays these topics to rebuild in-memory state.
- **Redis cache**: Used for caching client credentials, auth providers, and other hot data
## Key Abstractions
- Purpose: Represents the lifecycle of a single MQTT client connection. Handles all messages sequentially per client.
- Examples: `application/src/main/java/org/thingsboard/mqtt/broker/actors/client/ClientActor.java`
- Pattern: Actor model - each client has a dedicated mailbox, messages processed one at a time. Two dispatchers: `client-dispatcher` for client actors, `persisted-device-dispatcher` for device persistence actors.
- Purpose: Efficient topic filter matching supporting MQTT wildcards (`+` and `#`)
- Examples: `application/src/main/java/org/thingsboard/mqtt/broker/service/subscription/ConcurrentMapSubscriptionTrie.java`
- Pattern: Trie data structure with `ConcurrentHashMap` at each node, `ReadWriteLock` for clear operations
- Purpose: Routes messages to subscribers, handling local vs. remote delivery transparently
- Examples: `application/src/main/java/org/thingsboard/mqtt/broker/service/processing/downlink/DownLinkProxyImpl.java`
- Pattern: Location-transparent proxy - checks if target client is on this node; if yes, delivers directly; if not, publishes to the target node's Kafka downlink topic
- Purpose: Handles QoS 1/2 message persistence for offline clients
- Examples: `application/src/main/java/org/thingsboard/mqtt/broker/service/mqtt/persistence/MsgPersistenceManagerImpl.java`
- Pattern: Differentiates between "Application" clients (per-client Kafka topics) and "Device" clients (shared persistence queue with database storage)
- Purpose: Cluster-wide coordination for client session lifecycle (connect, disconnect, takeover)
- Examples: `application/src/main/java/org/thingsboard/mqtt/broker/service/mqtt/client/event/DefaultClientSessionEventService.java`
- Pattern: Request/response via dedicated Kafka topics (`tbmq.client.session.event.request` / `tbmq.client.session.event.response.<serviceId>`)
## Entry Points
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/ThingsboardMqttBrokerApplication.java`
- Triggers: JVM startup, Spring Boot auto-configuration
- Responsibilities: Bootstrap Spring context with config name `thingsboard-mqtt-broker`
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/server/tcp/MqttTcpServerBootstrap.java`
- Triggers: `ApplicationReadyEvent` (Spring)
- Responsibilities: Start Netty server on port 1883 (configurable)
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/server/tls/`
- Triggers: `ApplicationReadyEvent` (conditional on `listener.ssl.enabled=true`)
- Responsibilities: Start Netty server on port 8883 with TLS
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/server/ws/` and `server/wss/`
- Triggers: `ApplicationReadyEvent`
- Responsibilities: Start WebSocket (port 8084) and WSS (port 8085) listeners
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/controller/`
- Triggers: Spring Boot embedded Tomcat (port 8083)
- Responsibilities: Admin management API
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/install/ThingsboardMqttBrokerInstallService.java`
- Triggers: Spring profile `install`
- Responsibilities: Database schema creation, initial data loading, schema upgrades
- Location: `integration/executor/src/main/java/org/thingsboard/mqtt/broker/integration/`
- Triggers: Separate JVM process
- Responsibilities: Execute integrations (MQTT, HTTP, Kafka) that bridge TBMQ with external systems
## Error Handling
- `MqttSessionHandler.exceptionCaught()` catches transport-level errors (SSL, I/O, protocol violations) and triggers client disconnect
- `ClientActor.doProcess()` catches exceptions in MQTT message handling and sends `MqttDisconnectMsg` with appropriate reason codes (MQTT 5 reason codes supported)
- Kafka consumer services use configurable `AckStrategy` (SKIP_ALL or RETRY_ALL with configurable retry count) for message processing failures
- REST controllers use `BaseController` exception handling with `ThingsboardException` and `ThingsboardErrorCode`
- `FullMsgQueueException` handles pre-connect message queue overflow in actor state
## Cross-Cutting Concerns
- REST API: JWT tokens (access + refresh) via `JwtTokenFactory`, Spring Security filter chain in `SecurityConfiguration`
- MQTT clients: Pluggable auth providers configured via `MqttAuthProvider` entity. Supports: Basic (username/password), SSL certificate, SCRAM (enhanced authentication), HTTP external auth. Authorization rules with regex-based topic patterns via `AuthorizationRuleService`.
- Per-client incoming message limits (`RateLimitService.checkIncomingLimits()`)
- Total system-wide message limits (`RateLimitService.isTotalMsgsLimitEnabled()`)
- Outgoing message limits (device persisted messages)
- `RateLimitBatchProcessor` for batched rate limit checks
## Scalability Approach
- Multiple TBMQ broker nodes can run simultaneously
- Each node has a unique `serviceId` (from `ServiceInfoProvider`)
- All published messages go through the shared `tbmq.msg.all` Kafka topic (partitioned). Consumers in each node process a subset of partitions.
- Per-node Kafka topics for downlink delivery: `tbmq.msg.downlink.basic.<serviceId>` and `tbmq.msg.downlink.persisted.<serviceId>`
- Client session events use request/response topics for cluster-wide coordination of CONNECT/DISCONNECT
- Client sessions and subscriptions are stored in compacted Kafka topics, replayed on startup to rebuild in-memory state on each node
- MQTT spec requires only one active session per client ID across the cluster
- `ClientSessionEventService` uses Kafka-based request/response to ensure session takeover when a client reconnects to a different node
- `DisconnectClientCommandQueueFactory` provides per-node disconnect command topics
- "Application" clients get per-client Kafka topics for persistent message storage (high throughput, ordered delivery)
- "Device" clients share a common persistence queue (`tbmq.msg.persisted`) with database-backed storage (`DeviceMsgService`)
- Shared subscriptions supported with round-robin distribution strategy
## Kafka Topic Architecture
| Topic Pattern | Purpose | Type |
|---|---|---|
| `tbmq.msg.all` | All published messages | Partitioned |
| `tbmq.msg.downlink.basic.<serviceId>` | Non-persistent downlink to specific node | Per-node |
| `tbmq.msg.downlink.persisted.<serviceId>` | Persistent downlink to specific node | Per-node |
| `tbmq.client.session` | Client session state (compacted) | Compacted |
| `tbmq.client.subscriptions` | Client subscriptions (compacted) | Compacted |
| `tbmq.msg.retained` | Retained messages (compacted) | Compacted |
| `tbmq.client.session.event.request` | Session connect/disconnect requests | Partitioned |
| `tbmq.client.session.event.response.<serviceId>` | Session event responses | Per-node |
| `tbmq.client.disconnect.<serviceId>` | Disconnect commands to specific node | Per-node |
| `tbmq.msg.persisted` | Device persistent messages | Partitioned |
| `tbmq.msg.app.<clientId>` | Application client persistent messages | Per-client |
| `tbmq.client.blocked` | Blocked client list (compacted) | Compacted |
| `tbmq.sys.historical.data` | Historical stats data | Shared |
| `tbmq.sys.internode.notifications.<serviceId>` | Inter-node notifications | Per-node |
<!-- GSD:architecture-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd:quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd:debug` for investigation and bug fixing
- `/gsd:execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->



<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd:profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
