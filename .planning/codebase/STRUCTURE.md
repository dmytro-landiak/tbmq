# Codebase Structure

**Analysis Date:** 2026-03-26

## Directory Layout

```
tbmq/
├── application/              # Main Spring Boot application (broker server)
├── common/                   # Shared libraries (multi-module)
│   ├── actor/                # Generic actor framework
│   ├── cache/                # Redis cache configuration
│   ├── dao-api/              # DAO interfaces (service-facing)
│   ├── data/                 # Shared data models and DTOs
│   ├── integration/          # Integration API interfaces
│   │   ├── cluster-integration-api/  # Cluster-level integration API
│   │   └── integration-api/          # Core integration abstractions
│   ├── queue/                # Kafka queue abstractions and implementations
│   ├── stats/                # Metrics/statistics framework
│   └── util/                 # Common utilities (Jackson, threading, rate limits)
├── dao/                      # Database access layer (PostgreSQL JPA)
├── docker/                   # Docker compose files and configs
├── integration/              # Integration executor (separate deployable)
│   └── executor/             # Standalone integration executor service
├── k8s/                      # Kubernetes deployment configs
│   ├── aws/
│   ├── azure/
│   ├── gcp/
│   ├── helm/
│   └── minikube/
├── msa/                      # Microservices assembly (Docker images)
│   ├── black-box-tests/      # Integration test Docker configs
│   ├── integration/          # Integration executor Docker build
│   ├── mqtt-broker/          # Broker Docker build
│   └── tbmq/                 # TBMQ Docker compose configs
├── packaging/                # OS packaging (deb/rpm)
│   └── java/
├── ui-ngx/                   # Angular admin UI
│   ├── esbuild/              # Custom ESBuild config
│   └── src/                  # Angular source code
├── pom.xml                   # Root Maven POM
└── lombok.config             # Lombok settings
```

## Directory Purposes

**application/**
- Purpose: Main broker application module. Contains all server-side logic.
- Contains: Spring Boot main class, MQTT handlers, actors, services, controllers, configuration
- Key files:
  - `application/src/main/java/org/thingsboard/mqtt/broker/ThingsboardMqttBrokerApplication.java` - Main entry point
  - `application/src/main/resources/thingsboard-mqtt-broker.yml` - Primary configuration file
  - `application/src/main/data/upgrade/` - SQL upgrade scripts
  - `application/src/main/conf/` - External configuration templates
  - `application/src/main/resources/i18n/` - Internationalization messages
  - `application/src/main/resources/templates/` - Email templates

**common/actor/**
- Purpose: Reusable actor system framework (not MQTT-specific)
- Contains: `TbActorSystem`, `TbActor`, `TbActorMailbox`, dispatchers, lifecycle management
- Key files:
  - `common/actor/src/main/java/org/thingsboard/mqtt/broker/actors/TbActorSystem.java`
  - `common/actor/src/main/java/org/thingsboard/mqtt/broker/actors/TbActorMailbox.java`
  - `common/actor/src/main/java/org/thingsboard/mqtt/broker/actors/AbstractTbActor.java`

**common/cache/**
- Purpose: Redis cache configuration (Lettuce client, standalone/cluster/sentinel)
- Contains: Cache specs, Redis connection managers, cache stats
- Key files:
  - `common/cache/src/main/java/org/thingsboard/mqtt/broker/cache/TBRedisCacheConfiguration.java`
  - `common/cache/src/main/java/org/thingsboard/mqtt/broker/cache/CacheConstants.java`
  - `common/cache/src/main/java/org/thingsboard/mqtt/broker/cache/LettuceConnectionManager.java`

**common/dao-api/**
- Purpose: Service-facing DAO interfaces (decouples service layer from DAO implementation)
- Contains: Interfaces for client credentials, events, messages, settings, timeseries, user, websocket DAOs
- Key packages:
  - `common/dao-api/src/main/java/org/thingsboard/mqtt/broker/dao/client/` - Client credential DAO interfaces
  - `common/dao-api/src/main/java/org/thingsboard/mqtt/broker/dao/messages/` - Device message DAO interface
  - `common/dao-api/src/main/java/org/thingsboard/mqtt/broker/dao/integration/` - Integration DAO interface

**common/data/**
- Purpose: Shared data models, enums, DTOs used across all modules
- Contains: Entity classes (`ClientSession`, `ClientInfo`, `SessionInfo`, `Integration`), credential types, event types, subscription models
- Key files:
  - `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/ClientSessionInfo.java`
  - `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/ClientType.java` - APPLICATION vs DEVICE distinction
  - `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/BrokerConstants.java` - Global constants
  - `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/credentials/` - Credential type definitions

**common/queue/**
- Purpose: Kafka messaging infrastructure - producers, consumers, topic factories, protobuf definitions
- Contains: Kafka templates, topic settings per message type, queue factory interfaces and Kafka implementations
- Key files:
  - `common/queue/src/main/proto/queue.proto` - Protobuf definitions for all message types
  - `common/queue/src/main/proto/integration.proto` - Protobuf for integration messages
  - `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/kafka/` - Kafka producer/consumer templates
  - `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/kafka/settings/` - Per-topic Kafka configuration classes
  - `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/provider/` - Queue factory interfaces and Kafka implementations

**common/stats/**
- Purpose: Metrics collection framework (Micrometer-based)
- Contains: StatsFactory, counters, timers, message stats
- Key files:
  - `common/stats/src/main/java/org/thingsboard/mqtt/broker/common/stats/StatsFactory.java`
  - `common/stats/src/main/java/org/thingsboard/mqtt/broker/common/stats/MessagesStats.java`

**common/util/**
- Purpose: General-purpose utilities shared across modules
- Contains: JSON utilities, threading, rate limiting, validators
- Key files:
  - `common/util/src/main/java/org/thingsboard/mqtt/broker/common/util/JacksonUtil.java`
  - `common/util/src/main/java/org/thingsboard/mqtt/broker/common/util/ThingsBoardExecutors.java`
  - `common/util/src/main/java/org/thingsboard/mqtt/broker/common/util/TbRateLimits.java`

**dao/**
- Purpose: PostgreSQL database access layer with Spring Data JPA
- Contains: JPA entities, repositories, SQL services, time-series storage, schema files
- Key files:
  - `dao/src/main/resources/sql/schema-entities.sql` - Database schema (13 tables)
  - `dao/src/main/resources/sql/schema-entities-idx.sql` - Database indexes
  - `dao/src/main/java/org/thingsboard/mqtt/broker/dao/model/sql/` - JPA entity classes
  - `dao/src/main/java/org/thingsboard/mqtt/broker/dao/sql/` - SQL DAO implementations
  - `dao/src/main/java/org/thingsboard/mqtt/broker/dao/sqlts/` - Time-series DAO (partitioned tables)

**integration/executor/**
- Purpose: Separate deployable service that executes integrations (MQTT, HTTP, Kafka bridges)
- Contains: Integration lifecycle management, per-protocol handlers, message processing
- Key packages:
  - `integration/executor/src/main/java/.../integration/service/integration/mqtt/` - MQTT integration handler
  - `integration/executor/src/main/java/.../integration/service/integration/http/` - HTTP integration handler
  - `integration/executor/src/main/java/.../integration/service/integration/kafka/` - Kafka integration handler
  - `integration/executor/src/main/java/.../integration/service/processing/` - Message processing and backpressure

**ui-ngx/**
- Purpose: Angular-based admin web UI
- Contains: Angular modules, components, services, assets
- Key structure:
  - `ui-ngx/src/app/core/` - Core services (API, auth, HTTP interceptors, guards)
  - `ui-ngx/src/app/modules/home/pages/` - Feature pages (sessions, credentials, subscriptions, monitoring, etc.)
  - `ui-ngx/src/app/modules/login/` - Login page
  - `ui-ngx/src/app/shared/` - Shared components, models, pipes
  - `ui-ngx/src/assets/` - Static assets (fonts, images, locale files)
  - `ui-ngx/src/environments/` - Environment configurations

## Key File Locations

**Entry Points:**
- `application/src/main/java/org/thingsboard/mqtt/broker/ThingsboardMqttBrokerApplication.java`: Main Spring Boot application
- `integration/executor/src/main/java/org/thingsboard/mqtt/broker/integration/`: Integration executor entry point

**Configuration:**
- `application/src/main/resources/thingsboard-mqtt-broker.yml`: Primary config (env var driven)
- `application/src/main/conf/`: External config templates for deployment
- `integration/executor/src/main/conf/`: Integration executor config templates
- `integration/executor/src/main/resources/`: Integration executor application config

**Core Logic:**
- `application/src/main/java/org/thingsboard/mqtt/broker/server/MqttSessionHandler.java`: MQTT protocol entry
- `application/src/main/java/org/thingsboard/mqtt/broker/actors/client/ClientActor.java`: Per-client state machine
- `application/src/main/java/org/thingsboard/mqtt/broker/service/processing/MsgDispatcherServiceImpl.java`: Message routing
- `application/src/main/java/org/thingsboard/mqtt/broker/service/subscription/ConcurrentMapSubscriptionTrie.java`: Topic matching

**Database Schema:**
- `dao/src/main/resources/sql/schema-entities.sql`: Table definitions
- `dao/src/main/resources/sql/schema-entities-idx.sql`: Index definitions
- `application/src/main/data/upgrade/`: Schema migration scripts

**Protobuf Definitions:**
- `common/queue/src/main/proto/queue.proto`: Core message types (PublishMsgProto, SessionInfoProto, etc.)
- `common/queue/src/main/proto/integration.proto`: Integration-specific message types

**Testing:**
- `application/src/test/java/org/thingsboard/mqtt/broker/`: Unit tests mirroring main source
- `dao/src/test/java/org/thingsboard/mqtt/broker/dao/`: DAO tests
- `msa/black-box-tests/`: Docker-based integration tests

## Application Module Package Organization

```
org.thingsboard.mqtt.broker/
├── ThingsboardMqttBrokerApplication.java
├── actors/
│   ├── client/                 # ClientActor and supporting services
│   │   ├── messages/           # Actor message types (SessionInitMsg, MqttConnectMsg, etc.)
│   │   ├── service/            # Actor-internal services (connect, subscribe, channel)
│   │   └── state/              # Actor state management (SessionState, ClientActorState)
│   ├── config/                 # Actor system lifecycle and dispatchers
│   ├── device/                 # Device-specific actor configuration
│   ├── msg/                    # Base message types (TbActorMsg, MsgType enum)
│   ├── service/                # Actor processing metrics
│   └── shared/                 # Shared actor utilities
├── adaptor/                    # Protobuf <-> domain converters (ProtoConverter, NettyMqttConverter)
├── config/                     # Spring configuration classes
│   ├── annotations/            # Custom annotations (@AfterStartUp, @ApiOperation)
│   └── bcrypt/                 # BCrypt configuration
├── controller/                 # REST API controllers
├── dto/                        # REST-specific DTOs
├── exception/                  # Custom exceptions
├── install/                    # Install service (schema creation, upgrades)
├── server/                     # Netty MQTT server infrastructure
│   ├── ip/                     # IP extraction (proxy protocol)
│   ├── tcp/                    # TCP listener bootstrap
│   ├── tls/                    # TLS listener bootstrap
│   ├── traffic/                # Traffic logging handler
│   ├── ws/                     # WebSocket listener
│   ├── wshandler/              # WebSocket-specific Netty handlers
│   └── wss/                    # Secure WebSocket listener
├── service/
│   ├── analysis/               # Client event logging (ClientLogger)
│   ├── auth/                   # MQTT authentication and authorization
│   ├── connectivity/           # Device connectivity info
│   ├── entity/                 # Entity-level services
│   ├── historical/             # Historical stats recording
│   ├── http/                   # HTTP auth provider client
│   ├── install/                # DB schema service, data loaders, upgrades
│   ├── integration/            # Integration management (TBMQ-side)
│   ├── limits/                 # Rate limiting (incoming, outgoing, total)
│   ├── mail/                   # Email service
│   ├── mqtt/
│   │   ├── auth/               # MQTT auth handlers
│   │   ├── client/
│   │   │   ├── blocked/        # Blocked client management
│   │   │   ├── cleanup/        # Session cleanup
│   │   │   ├── credentials/    # Client credentials management
│   │   │   ├── disconnect/     # Disconnect handling
│   │   │   ├── event/          # Session event coordination (cluster)
│   │   │   └── session/        # Client session cache and persistence
│   │   ├── delivery/           # MQTT message delivery
│   │   ├── flow/               # MQTT flow control (receive maximum)
│   │   ├── keepalive/          # MQTT keep-alive monitoring
│   │   ├── persistence/
│   │   │   ├── application/    # Application client persistence (Kafka-based)
│   │   │   ├── device/         # Device client persistence (DB-based)
│   │   │   └── integration/    # Integration persistence
│   │   ├── retain/             # Retained message management (trie + Kafka)
│   │   ├── validation/         # Message validation
│   │   └── will/               # Will message handling
│   ├── notification/           # Internal notifications
│   ├── processing/
│   │   ├── data/               # Processing data structures (MsgSubscriptions)
│   │   ├── downlink/           # Downlink message routing
│   │   │   ├── basic/          # Non-persistent downlink
│   │   │   └── persistent/     # Persistent downlink
│   │   └── shared/             # Shared subscription processing
│   ├── provider/               # Service providers
│   ├── security/               # REST API security (JWT, Spring Security)
│   ├── stats/                  # Application-level stats management
│   ├── subscription/
│   │   ├── data/               # Subscription data types
│   │   ├── integration/        # Integration subscriptions
│   │   └── shared/             # Shared subscription management
│   ├── system/                 # System info services
│   ├── ttl/                    # TTL cleanup (events, timeseries)
│   └── user/                   # User management
├── session/                    # Client session context (ClientSessionCtx, ClientMqttActorManager)
├── ssl/                        # SSL configuration and credential loading
│   └── config/
└── util/                       # Application-level utilities
```

## Naming Conventions

**Files:**
- Java classes: PascalCase (`ClientActor.java`, `MsgDispatcherServiceImpl.java`)
- Interface + Impl pattern: `FooService.java` (interface) + `FooServiceImpl.java` (implementation)
- Protobuf: snake_case file names (`queue.proto`), PascalCase message names (`PublishMsgProto`)
- SQL files: kebab-case (`schema-entities.sql`)
- Angular: kebab-case with suffix (`client-credentials.component.ts`, `auth.service.ts`)

**Directories:**
- Java packages: lowercase, hyphen-separated for multi-word module names in Maven (`dao-api`, `common/integration`)
- Java package structure: `org.thingsboard.mqtt.broker.<module>.<feature>`

**Class Naming Patterns:**
- Services: `<Feature>Service` / `<Feature>ServiceImpl`
- Controllers: `<Entity>Controller`
- DAO: `<Entity>Dao` / `Sql<Entity>Dao`
- JPA Entities: `<Entity>Entity`
- Kafka Settings: `<Feature>KafkaSettings`
- Queue Factories: `Kafka<Feature>QueueFactory` implements `<Feature>QueueFactory`
- Actors: `<Type>Actor`, `<Type>ActorCreator`
- Configuration: `<Feature>Configuration`, `<Feature>Properties`

## Where to Add New Code

**New MQTT Feature (e.g., new message type handler):**
- MQTT handling: `application/src/main/java/org/thingsboard/mqtt/broker/service/mqtt/<feature>/`
- Actor message type: `application/src/main/java/org/thingsboard/mqtt/broker/actors/client/messages/mqtt/`
- Add case to: `application/src/main/java/org/thingsboard/mqtt/broker/server/MqttSessionHandler.java` (processMqttMsg switch)
- Add case to: `application/src/main/java/org/thingsboard/mqtt/broker/actors/client/ClientActor.java` (doProcess switch)
- Tests: `application/src/test/java/org/thingsboard/mqtt/broker/service/mqtt/<feature>/`

**New REST API Endpoint:**
- Controller: `application/src/main/java/org/thingsboard/mqtt/broker/controller/<Entity>Controller.java`
- Service: `application/src/main/java/org/thingsboard/mqtt/broker/service/<feature>/`
- DTO: `application/src/main/java/org/thingsboard/mqtt/broker/dto/`
- Tests: `application/src/test/java/org/thingsboard/mqtt/broker/controller/`

**New Database Entity:**
- Data model: `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/<Entity>.java`
- DAO interface: `common/dao-api/src/main/java/org/thingsboard/mqtt/broker/dao/<feature>/<Entity>Dao.java`
- JPA entity: `dao/src/main/java/org/thingsboard/mqtt/broker/dao/model/sql/<Entity>Entity.java`
- JPA repository: `dao/src/main/java/org/thingsboard/mqtt/broker/dao/sql/<feature>/`
- Schema: `dao/src/main/resources/sql/schema-entities.sql` (CREATE TABLE)
- Upgrade: `application/src/main/data/upgrade/basic/` (migration script)

**New Kafka Topic:**
- Kafka settings: `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/kafka/settings/<Feature>KafkaSettings.java`
- Queue factory interface: `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/provider/<Feature>QueueFactory.java`
- Kafka factory impl: `common/queue/src/main/java/org/thingsboard/mqtt/broker/queue/provider/Kafka<Feature>QueueFactory.java`
- Protobuf definition: `common/queue/src/main/proto/queue.proto` (add message type)
- Configuration: Add topic settings to `application/src/main/resources/thingsboard-mqtt-broker.yml` under `queue.kafka.<topic-name>`

**New Integration Type:**
- Integration handler: `integration/executor/src/main/java/.../integration/service/integration/<type>/`
- Integration API: `common/integration/integration-api/`

**New UI Page:**
- Page component: `ui-ngx/src/app/modules/home/pages/<feature>/`
- Models: `ui-ngx/src/app/shared/models/`
- API service: `ui-ngx/src/app/core/http/`
- Add route in home module routing

**Utilities:**
- Shared across modules: `common/util/src/main/java/org/thingsboard/mqtt/broker/common/util/`
- Application-specific: `application/src/main/java/org/thingsboard/mqtt/broker/util/`

## Module Dependency Graph

```
application  ──────> common/data
    │                common/dao-api
    │                common/queue
    │                common/actor
    │                common/cache
    │                common/stats
    │                common/util
    │                common/integration/cluster-integration-api
    │                dao
    │
dao ───────────────> common/data
    │                common/dao-api
    │                common/util
    │
common/queue ──────> common/data
    │                common/stats
    │                common/util
    │
common/cache ──────> common/data
    │
common/dao-api ────> common/data
    │
common/actor ──────> (no internal deps)
    │
common/stats ──────> (no internal deps)
    │
common/util ───────> common/data
    │
common/data ───────> (no internal deps, only external libs)
    │
integration/executor > common/data
    │                  common/queue
    │                  common/cache
    │                  common/stats
    │                  common/util
    │                  common/integration/integration-api
    │                  dao
```

## Special Directories

**application/src/main/data/upgrade/**
- Purpose: SQL upgrade scripts for schema migration between versions
- Generated: No (manually maintained)
- Committed: Yes

**common/queue/src/main/proto/**
- Purpose: Protobuf `.proto` definitions that generate Java classes at build time
- Generated: `.proto` files are committed; generated Java goes to `target/`
- Committed: `.proto` files yes, generated code no

**msa/**
- Purpose: Docker image assembly and docker-compose configurations
- Generated: No
- Committed: Yes

**docker/**
- Purpose: Development/deployment Docker configurations (HAProxy, scripts, backup)
- Generated: No
- Committed: Yes

**k8s/**
- Purpose: Kubernetes deployment manifests for AWS, Azure, GCP, Helm, Minikube
- Generated: No
- Committed: Yes

**ui-ngx/node_modules/**
- Purpose: NPM dependencies for Angular UI
- Generated: Yes (npm install)
- Committed: No (gitignored)

## Database Tables (PostgreSQL)

| Table | Purpose |
|---|---|
| `admin_settings` | System admin settings (JSON) |
| `broker_user` | Admin UI users |
| `user_credentials` | User passwords and tokens |
| `mqtt_auth_provider` | MQTT auth provider configurations |
| `mqtt_client_credentials` | MQTT client credentials (basic, cert, SCRAM) |
| `application_session_ctx` | Application client session context (QoS state) |
| `generic_client_session_ctx` | Device client session context (QoS2 packet IDs) |
| `application_shared_subscription` | Shared subscription configurations |
| `websocket_connection` | WebSocket test client connections |
| `websocket_subscription` | WebSocket test client subscriptions |
| `unauthorized_client` | Unauthorized connection attempts log |
| `integration` | Integration configurations |
| `ts_kv` | Time-series data (partitioned by timestamp) |
| `ts_kv_latest` | Latest time-series values |
| `ts_kv_dictionary` | Time-series key dictionary |
| `stats_event` | Statistics events (partitioned) |
| `lc_event` | Lifecycle events (partitioned) |
| `error_event` | Error events (partitioned) |
| `tb_schema_settings` | Schema version tracking |

---

*Structure analysis: 2026-03-26*
