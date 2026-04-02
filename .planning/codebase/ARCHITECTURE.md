# Architecture

**Analysis Date:** 2026-03-26

## Pattern Overview

**Overall:** Modular monolith with horizontal scaling via Kafka-based message partitioning

**Key Characteristics:**
- Spring Boot application with Netty-based MQTT protocol handling
- Actor model for per-client session management (one actor per MQTT client)
- Apache Kafka as the central message bus for publish/subscribe routing and inter-node communication
- PostgreSQL for persistent entity storage, Redis for caching
- Separate "Integration Executor" microservice for external integrations (MQTT, HTTP, Kafka)
- Protobuf serialization for all Kafka messages

## Layers

**Transport Layer (Netty):**
- Purpose: Accept MQTT connections over TCP, TLS, WebSocket, and WSS
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/server/`
- Contains: Server bootstraps, channel initializers, and the `MqttSessionHandler`
- Depends on: Netty, Actor system (via `ClientMqttActorManager`)
- Used by: MQTT clients connecting to the broker
- Key files:
  - `application/src/main/java/org/thingsboard/mqtt/broker/server/AbstractMqttServerBootstrap.java` - Base Netty server bootstrap
  - `application/src/main/java/org/thingsboard/mqtt/broker/server/MqttSessionHandler.java` - Inbound Netty handler, first touch point for all MQTT messages
  - `application/src/main/java/org/thingsboard/mqtt/broker/server/tcp/MqttTcpServerBootstrap.java` - TCP listener (port 1883)
  - `application/src/main/java/org/thingsboard/mqtt/broker/server/tls/` - TLS listener (port 8883)
  - `application/src/main/java/org/thingsboard/mqtt/broker/server/ws/` - WebSocket listener (port 8084)
  - `application/src/main/java/org/thingsboard/mqtt/broker/server/wss/` - WebSocket Secure listener (port 8085)

**Actor Layer:**
- Purpose: Per-client concurrency isolation using the actor model; each MQTT client ID gets its own actor
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/actors/`
- Contains: Actor system, client actors, device actors, message types
- Depends on: Common actor framework (`common/actor/`), service layer
- Used by: Transport layer (via `ClientMqttActorManager`)
- Key files:
  - `application/src/main/java/org/thingsboard/mqtt/broker/actors/client/ClientActor.java` - Main per-client actor handling all MQTT message types
  - `application/src/main/java/org/thingsboard/mqtt/broker/actors/ActorSystemContext.java` - Wiring between actor system and Spring services
  - `application/src/main/java/org/thingsboard/mqtt/broker/actors/config/ActorSystemLifecycle.java` - Actor system initialization with two dispatchers: `client-dispatcher` and `persisted-device-dispatcher`
  - `application/src/main/java/org/thingsboard/mqtt/broker/session/ClientMqttActorManager.java` - Interface bridging transport and actor layer
  - `common/actor/src/main/java/org/thingsboard/mqtt/broker/actors/TbActorSystem.java` - Generic actor system framework

**Service Layer:**
- Purpose: Core business logic for MQTT message processing, subscriptions, persistence, authentication
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/service/`
- Contains: MQTT services, subscription management, message processing, authentication, rate limiting
- Depends on: DAO layer, Queue layer, Cache layer
- Used by: Actor layer
- Key sub-packages:
  - `service/mqtt/` - MQTT protocol services (publish, subscribe, retain, will messages, flow control, persistence)
  - `service/subscription/` - Subscription trie and subscription management
  - `service/processing/` - Message dispatching and consumption from Kafka
  - `service/auth/` - MQTT client authentication and authorization
  - `service/security/` - REST API security (JWT-based)
  - `service/mqtt/client/session/` - Client session cache and persistence
  - `service/mqtt/client/event/` - Client session event processing (connect/disconnect coordination across cluster)
  - `service/integration/` - Platform integration management

**Controller Layer (REST API):**
- Purpose: Management REST API for admin UI operations
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/controller/`
- Contains: Spring MVC controllers for sessions, credentials, subscriptions, retained messages, etc.
- Depends on: Service layer, Security
- Used by: Admin UI (`ui-ngx/`)
- Key files:
  - `application/src/main/java/org/thingsboard/mqtt/broker/controller/BaseController.java` - Base controller with common error handling
  - `application/src/main/java/org/thingsboard/mqtt/broker/controller/ClientSessionController.java` - Session management API
  - `application/src/main/java/org/thingsboard/mqtt/broker/controller/MqttClientCredentialsController.java` - Credential CRUD
  - `application/src/main/java/org/thingsboard/mqtt/broker/controller/AuthController.java` - REST login/token refresh
  - `application/src/main/java/org/thingsboard/mqtt/broker/controller/IntegrationController.java` - Integration management

**DAO Layer:**
- Purpose: Database access (PostgreSQL) via Spring Data JPA
- Location: `dao/src/main/java/org/thingsboard/mqtt/broker/dao/`
- Contains: JPA repositories, entity models, SQL services
- Depends on: PostgreSQL, Spring Data JPA
- Used by: Service layer
- Key sub-packages:
  - `dao/model/sql/` - JPA entity classes
  - `dao/sql/` - SQL-specific implementations
  - `dao/sqlts/` - Time-series data storage
  - `dao/client/` - Client credentials, sessions, connectivity settings
  - `dao/messages/` - Device message persistence
  - `dao/integration/` - Integration persistence

**Queue Layer:**
- Purpose: Kafka-based messaging for publish/subscribe routing and inter-node communication
- Location: `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/`
- Contains: Kafka producer/consumer templates, topic configuration, queue factories
- Depends on: Apache Kafka, Protobuf
- Used by: Service layer (processing, session events, downlink)
- Key files:
  - `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/kafka/TbKafkaProducerTemplate.java` - Kafka producer wrapper
  - `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/kafka/TbKafkaConsumerTemplate.java` - Kafka consumer wrapper
  - `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/provider/` - Factory interfaces and Kafka implementations for each topic type
  - `common/queue/src/main/proto/queue.proto` - Protobuf definitions for all Kafka messages

**Common Layer:**
- Purpose: Shared data models, utilities, cache configuration, statistics
- Location: `common/`
- Contains: Data models, actor framework, cache (Redis), queue abstractions, stats, utilities
- Depends on: External libraries only
- Used by: All other layers

## Data Flow

**MQTT PUBLISH Message Flow (non-persistent client):**

1. MQTT client sends PUBLISH message over TCP/TLS/WS
2. `MqttSessionHandler.channelRead()` receives the Netty `MqttPublishMessage`
3. Rate limiting is checked via `RateLimitService`
4. Message is forwarded to `ClientMqttActorManager.processMqttMsg()` which routes to the client's `ClientActor`
5. `ClientActor.doProcess()` delegates to `MqttMessageHandler.process()` for publish handling
6. `MsgDispatcherService.persistPublishMsg()` serializes to Protobuf and publishes to Kafka topic `tbmq.msg.all`
7. `PublishMsgConsumerServiceImpl` consumers (multiple threads) poll `tbmq.msg.all`
8. `MsgDispatcherService.processPublishMsg()` looks up matching subscriptions via `SubscriptionService.getSubscriptions()` (trie-based topic matching)
9. For each matching non-persistent subscriber on this node: `BasicDownLinkProcessor` delivers directly via `MqttMsgDeliveryService`
10. For each matching non-persistent subscriber on another node: message is published to that node's `tbmq.msg.downlink.basic.<serviceId>` Kafka topic
11. For persistent subscribers (device/application): message is routed to persistence queues

**MQTT CONNECT Flow:**

1. `MqttSessionHandler` receives CONNECT, extracts client ID, creates `ClientSessionCtx`
2. `ClientMqttActorManager.initSession()` creates/finds a `ClientActor` for the client ID
3. `ClientActor` receives `SESSION_INIT_MSG`, delegates to `ActorProcessor.onInit()`
4. Authentication is performed via configurable auth providers (Basic, TLS, SCRAM, HTTP)
5. `ConnectService.startConnection()` publishes a connection request to `tbmq.client.session.event.request` Kafka topic
6. `ClientSessionEventConsumer` processes the request, coordinates across the cluster (only one active session per client ID)
7. Response arrives via `tbmq.client.session.event.response.<serviceId>` topic
8. `ConnectService.acceptConnection()` sends CONNACK to the client, actor state transitions to CONNECTED

**Subscription Management:**

1. Client sends SUBSCRIBE
2. `ClientActor` receives `MQTT_SUBSCRIBE_MSG`, delegates to `MqttMessageHandler`
3. Subscriptions are persisted to `tbmq.client.subscriptions` Kafka topic (compacted log)
4. `SubscriptionService` updates the in-memory `ConcurrentMapSubscriptionTrie`
5. All broker nodes consume the subscriptions topic to maintain a consistent trie

**State Management:**
- **In-memory subscription trie**: `ConcurrentMapSubscriptionTrie` - concurrent trie data structure for O(topic-depth) subscription matching
- **In-memory retained message trie**: `ConcurrentMapRetainMsgTrie` - stores retained messages for topic matching
- **Client session cache**: In-memory `ConcurrentHashMap` tracking which clients are connected to which broker node
- **Kafka compacted topics**: Used as the source of truth for client sessions, subscriptions, retained messages, and blocked clients. On startup, each node replays these topics to rebuild in-memory state.
- **Redis cache**: Used for caching client credentials, auth providers, and other hot data

## Key Abstractions

**ClientActor:**
- Purpose: Represents the lifecycle of a single MQTT client connection. Handles all messages sequentially per client.
- Examples: `application/src/main/java/org/thingsboard/mqtt/broker/actors/client/ClientActor.java`
- Pattern: Actor model - each client has a dedicated mailbox, messages processed one at a time. Two dispatchers: `client-dispatcher` for client actors, `persisted-device-dispatcher` for device persistence actors.

**SubscriptionTrie:**
- Purpose: Efficient topic filter matching supporting MQTT wildcards (`+` and `#`)
- Examples: `application/src/main/java/org/thingsboard/mqtt/broker/service/subscription/ConcurrentMapSubscriptionTrie.java`
- Pattern: Trie data structure with `ConcurrentHashMap` at each node, `ReadWriteLock` for clear operations

**DownLinkProxy:**
- Purpose: Routes messages to subscribers, handling local vs. remote delivery transparently
- Examples: `application/src/main/java/org/thingsboard/mqtt/broker/service/processing/downlink/DownLinkProxyImpl.java`
- Pattern: Location-transparent proxy - checks if target client is on this node; if yes, delivers directly; if not, publishes to the target node's Kafka downlink topic

**MsgPersistenceManager:**
- Purpose: Handles QoS 1/2 message persistence for offline clients
- Examples: `application/src/main/java/org/thingsboard/mqtt/broker/service/mqtt/persistence/MsgPersistenceManagerImpl.java`
- Pattern: Differentiates between "Application" clients (per-client Kafka topics) and "Device" clients (shared persistence queue with database storage)

**ClientSessionEventService:**
- Purpose: Cluster-wide coordination for client session lifecycle (connect, disconnect, takeover)
- Examples: `application/src/main/java/org/thingsboard/mqtt/broker/service/mqtt/client/event/DefaultClientSessionEventService.java`
- Pattern: Request/response via dedicated Kafka topics (`tbmq.client.session.event.request` / `tbmq.client.session.event.response.<serviceId>`)

## Entry Points

**Application Main:**
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/ThingsboardMqttBrokerApplication.java`
- Triggers: JVM startup, Spring Boot auto-configuration
- Responsibilities: Bootstrap Spring context with config name `thingsboard-mqtt-broker`

**MQTT TCP Server:**
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/server/tcp/MqttTcpServerBootstrap.java`
- Triggers: `ApplicationReadyEvent` (Spring)
- Responsibilities: Start Netty server on port 1883 (configurable)

**MQTT TLS Server:**
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/server/tls/`
- Triggers: `ApplicationReadyEvent` (conditional on `listener.ssl.enabled=true`)
- Responsibilities: Start Netty server on port 8883 with TLS

**WebSocket Servers:**
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/server/ws/` and `server/wss/`
- Triggers: `ApplicationReadyEvent`
- Responsibilities: Start WebSocket (port 8084) and WSS (port 8085) listeners

**HTTP REST API:**
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/controller/`
- Triggers: Spring Boot embedded Tomcat (port 8083)
- Responsibilities: Admin management API

**Install Service:**
- Location: `application/src/main/java/org/thingsboard/mqtt/broker/install/ThingsboardMqttBrokerInstallService.java`
- Triggers: Spring profile `install`
- Responsibilities: Database schema creation, initial data loading, schema upgrades

**Integration Executor (separate service):**
- Location: `integration/executor/src/main/java/org/thingsboard/mqtt/broker/integration/`
- Triggers: Separate JVM process
- Responsibilities: Execute integrations (MQTT, HTTP, Kafka) that bridge TBMQ with external systems

## Error Handling

**Strategy:** Multi-layered: protocol-level disconnect for MQTT errors, exception-based for service errors, Kafka retry strategies for message processing

**Patterns:**
- `MqttSessionHandler.exceptionCaught()` catches transport-level errors (SSL, I/O, protocol violations) and triggers client disconnect
- `ClientActor.doProcess()` catches exceptions in MQTT message handling and sends `MqttDisconnectMsg` with appropriate reason codes (MQTT 5 reason codes supported)
- Kafka consumer services use configurable `AckStrategy` (SKIP_ALL or RETRY_ALL with configurable retry count) for message processing failures
- REST controllers use `BaseController` exception handling with `ThingsboardException` and `ThingsboardErrorCode`
- `FullMsgQueueException` handles pre-connect message queue overflow in actor state

## Cross-Cutting Concerns

**Logging:** SLF4J with Logback. `ClientLogger` provides structured per-client event logging for analysis/debugging.

**Validation:** MQTT protocol validation in `MqttSessionHandler` (decoder results, message types). Entity validation in service layer. Topic name validation for Kafka topic creation safety.

**Authentication:**
- REST API: JWT tokens (access + refresh) via `JwtTokenFactory`, Spring Security filter chain in `SecurityConfiguration`
- MQTT clients: Pluggable auth providers configured via `MqttAuthProvider` entity. Supports: Basic (username/password), SSL certificate, SCRAM (enhanced authentication), HTTP external auth. Authorization rules with regex-based topic patterns via `AuthorizationRuleService`.

**Rate Limiting:** Bucket4j-based rate limiting at multiple levels:
- Per-client incoming message limits (`RateLimitService.checkIncomingLimits()`)
- Total system-wide message limits (`RateLimitService.isTotalMsgsLimitEnabled()`)
- Outgoing message limits (device persisted messages)
- `RateLimitBatchProcessor` for batched rate limit checks

**Statistics:** Micrometer-based metrics via `StatsFactory` and `StatsManager`. Counters for messages processed/dropped, subscription trie size, Kafka consumer lag. Historical stats persisted to time-series tables via `TbMessageStatsReportClient`.

## Scalability Approach

**Horizontal Scaling via Kafka Partitioning:**
- Multiple TBMQ broker nodes can run simultaneously
- Each node has a unique `serviceId` (from `ServiceInfoProvider`)
- All published messages go through the shared `tbmq.msg.all` Kafka topic (partitioned). Consumers in each node process a subset of partitions.
- Per-node Kafka topics for downlink delivery: `tbmq.msg.downlink.basic.<serviceId>` and `tbmq.msg.downlink.persisted.<serviceId>`
- Client session events use request/response topics for cluster-wide coordination of CONNECT/DISCONNECT
- Client sessions and subscriptions are stored in compacted Kafka topics, replayed on startup to rebuild in-memory state on each node

**Client Session Coordination:**
- MQTT spec requires only one active session per client ID across the cluster
- `ClientSessionEventService` uses Kafka-based request/response to ensure session takeover when a client reconnects to a different node
- `DisconnectClientCommandQueueFactory` provides per-node disconnect command topics

**Application vs. Device Client Types:**
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

---

*Architecture analysis: 2026-03-26*
