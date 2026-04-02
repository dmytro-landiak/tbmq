# External Integrations

**Analysis Date:** 2026-03-26

## Message Broker: Apache Kafka

**Role:** Central nervous system for all inter-broker communication, message persistence, and event distribution.

**Client:** `org.apache.kafka:kafka-clients` 3.9.1 (defined in `common/queue/pom.xml`)
**Connection:** `TB_KAFKA_SERVERS` env var, default `localhost:9092`
**Docker Image:** `apache/kafka:4.0.0` (KRaft mode, no ZooKeeper dependency)
**Configuration:** `application/src/main/resources/thingsboard-mqtt-broker.yml` under `queue.kafka.*`

**Kafka Topics (all prefixable via `TB_KAFKA_PREFIX`):**
| Topic | Purpose |
|-------|---------|
| `tbmq.msg.all` | All incoming PUBLISH messages (16 partitions default) |
| `tbmq.msg.persisted` | Device client persistent messages (12 partitions) |
| `tbmq.msg.app.*` | Per-application-client persisted messages |
| `tbmq.msg.retained` | Retained messages (1 partition, compacted) |
| `tbmq.client.session` | Client session state (1 partition, compacted) |
| `tbmq.client.subscriptions` | Client subscriptions (1 partition, compacted) |
| `tbmq.client.session.event.request` | Session event requests (24 partitions) |
| `tbmq.client.session.event.response.*` | Per-node session event responses |
| `tbmq.client.disconnect.*` | Per-node disconnect commands |
| `tbmq.msg.downlink.basic.*` | Per-node basic downlink messages (12 partitions) |
| `tbmq.msg.downlink.persisted.*` | Per-node persistent downlink messages (12 partitions) |
| `tbmq.sys.app.removed` | Application removal events |
| `tbmq.sys.historical.data` | Historical stats aggregation |
| `tbmq.sys.internode.notifications.*` | Per-node system notifications |
| `tbmq.client.blocked` | Blocked clients state (1 partition, compacted) |
| `tbmq.ie.downlink.http` | Integration executor downlink (HTTP type, 6 partitions) |
| `tbmq.ie.downlink.kafka` | Integration executor downlink (Kafka type, 6 partitions) |
| `tbmq.ie.downlink.mqtt` | Integration executor downlink (MQTT type, 6 partitions) |
| `tbmq.ie.uplink` | Integration executor uplink messages (6 partitions) |
| `tbmq.ie.uplink.notifications.*` | Per-node integration uplink notifications |
| `tbmq.msg.ie.*` | Per-integration message topics |

**Protobuf Schemas:**
- `common/queue/src/main/proto/queue.proto` - Core message and session event serialization.
- `common/queue/src/main/proto/integration.proto` - Integration message serialization.

**Key Consumer/Producer Configuration:**
- Default consumer partition assignment: `StickyAssignor`
- Default consumer session timeout: 10000 ms
- Default consumer max poll records: 2000
- Default producer acks: 1
- Default producer batch size: 16384 bytes
- Default compression: none
- Admin client: configurable via `TB_KAFKA_ADMIN_CONFIG`
- Shared connection/security config: `TB_KAFKA_DEFAULT_PRODUCER_CONSUMER_CONFIG` (supports SASL/SSL)

## Data Storage

**Primary Database: PostgreSQL**
- Version: 17 (Docker image: `postgres:17`)
- Connection: `SPRING_DATASOURCE_URL`, default `jdbc:postgresql://localhost:5432/thingsboard_mqtt_broker`
- Driver: `org.postgresql.Driver`
- ORM: Spring Data JPA + Hibernate
- Connection Pool: HikariCP (max pool size: 16, max lifetime: 600000 ms)
- DDL: `SPRING_JPA_HIBERNATE_DDL_AUTO` = `none` (schema managed by install scripts)
- Schema: `dao/src/main/resources/sql/schema-entities.sql`
- Indexes: `dao/src/main/resources/sql/schema-entities-idx.sql`

**Database Tables:**
| Table | Purpose |
|-------|---------|
| `tb_schema_settings` | Schema version tracking |
| `admin_settings` | Admin configuration (JSON) |
| `broker_user` | Web UI users |
| `user_credentials` | User passwords and tokens |
| `mqtt_auth_provider` | MQTT authentication provider configuration (JSONB) |
| `mqtt_client_credentials` | MQTT client credentials |
| `application_session_ctx` | Persistent application client session context |
| `generic_client_session_ctx` | Generic client session QoS2 context |
| `application_shared_subscription` | Shared subscription definitions |
| `ts_kv` | Time series key-value data (partitioned by range on `ts`) |
| `ts_kv_latest` | Latest time series values |
| `ts_kv_dictionary` | Time series key name dictionary |
| `websocket_connection` | WebSocket MQTT client connections (UI) |
| `websocket_subscription` | WebSocket MQTT client subscriptions (UI) |
| `unauthorized_client` | Failed auth attempt tracking |
| `integration` | Integration configurations |
| `stats_event` | Statistics events (partitioned) |
| `lc_event` | Lifecycle events (partitioned) |
| `error_event` | Error events (partitioned) |

**Time Series Partitioning:** Configurable via `SQL_TS_KV_PARTITIONING` (DAYS, MONTHS, YEARS, INDEFINITE). Batch inserts with configurable batch size/delay/threads for ts, ts_latest, unauthorized_client, and events.

**TTL Cleanup:** Automated cleanup via scheduled tasks:
- Time series: `SQL_TTL_TS_KEY_VALUE_TTL` (default 7 days)
- Unauthorized clients: `SQL_TTL_UNAUTHORIZED_CLIENT_TTL` (default 3 days)
- Events: `SQL_TTL_EVENTS_TTL_SEC` (default 14 days)

**Caching: Redis/Valkey**
- Provider: Valkey 8.0 (Redis-compatible, see `docker/cache/docker-compose.valkey.yml`)
- Clients: Jedis 5.1.5 and Lettuce 6.5.1.RELEASE (both supported)
- Spring Data Redis abstraction in `common/cache/pom.xml`
- Connection modes: standalone, cluster, sentinel (configured via `REDIS_CONNECTION_TYPE`)
- Connection: `REDIS_HOST`/`REDIS_PORT` (default `localhost:6379`)
- Rate Limiting: Bucket4j with Redis backend (`bucket4j_jdk17-jedis`)
- TLS support: `REDIS_SSL_ENABLED` with PEM cert configuration
- Pool config: max 128 connections, test on borrow/return/idle

**Cache Specs (TTLs):**
| Cache | Default TTL |
|-------|------------|
| `mqttClientCredentials` | 1440 min (1 day) |
| `basicCredentialsPassword` | 1 min |
| `sslRegexBasedCredentials` | 1440 min (1 day) |
| `clientSessionCredentials` | Eternal (0) |
| `clientMqttVersion` | Eternal (0) |

**File Storage:**
- None. No S3/GCS/blob storage integration detected.

## Authentication & Identity

**REST API Authentication:**
- Spring Security with JWT tokens
- JWT library: `io.jsonwebtoken:jjwt` 0.12.5
- Token expiration: 9000s (2.5 hours), refresh: 604800s (1 week)
- Token issuer: `thingsboard.io`
- Providers: `application/src/main/java/org/thingsboard/mqtt/broker/service/security/auth/rest/RestAuthenticationProvider.java`
- JWT auth: `application/src/main/java/org/thingsboard/mqtt/broker/service/security/auth/jwt/JwtAuthenticationProvider.java`
- Security config: `application/src/main/java/org/thingsboard/mqtt/broker/config/SecurityConfiguration.java`

**MQTT Client Authentication (5 providers):**
- `MQTT_BASIC` - Username/password. Implementation: `application/src/main/java/org/thingsboard/mqtt/broker/service/auth/providers/basic/BasicMqttClientAuthProvider.java`
- `X_509` - X.509 certificate chain (TLS client cert). Implementation: `application/src/main/java/org/thingsboard/mqtt/broker/service/auth/providers/ssl/SslMqttClientAuthProvider.java`
- `JWT` - JWT token-based MQTT auth. Implementation: `application/src/main/java/org/thingsboard/mqtt/broker/service/auth/providers/jwt/JwtMqttClientAuthProvider.java`
- `SCRAM` - SCRAM enhanced authentication (MQTT 5). Implementation: `application/src/main/java/org/thingsboard/mqtt/broker/service/auth/enhanced/ScramAuthCallbackHandler.java`
- `HTTP` - External HTTP service authentication. Implementation: `application/src/main/java/org/thingsboard/mqtt/broker/service/auth/providers/http/HttpMqttClientAuthProvider.java`

Provider type enum: `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/security/MqttAuthProviderType.java`
Provider configs (JSONB): `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/security/` (per-type configuration classes)
Auth routing: `application/src/main/java/org/thingsboard/mqtt/broker/service/auth/DefaultAuthorizationRoutingService.java`

## Platform Integrations (Outbound)

**Integration Executor** is a separate Spring Boot microservice that bridges MQTT messages to external systems.
- Main class: `integration/executor/src/main/java/org/thingsboard/mqtt/broker/integration/TbmqIntegrationExecutorApplication.java`
- Docker image: `thingsboard/tbmq-integration-executor`
- Communicates with TBMQ broker via Kafka (downlink/uplink topics)

**Integration Types:**
| Type | Implementation |
|------|---------------|
| HTTP | `integration/executor/src/main/java/org/thingsboard/mqtt/broker/integration/service/integration/http/HttpIntegration.java` |
| Kafka | `integration/executor/src/main/java/org/thingsboard/mqtt/broker/integration/service/integration/kafka/KafkaIntegration.java` |
| MQTT | `integration/executor/src/main/java/org/thingsboard/mqtt/broker/integration/service/integration/mqtt/MqttIntegration.java` |

**Integration API:**
- `common/integration/integration-api/` - Core integration interfaces
- `common/integration/cluster-integration-api/` - Cluster-aware integration API
- Rate limiting: `integration/executor/src/main/java/org/thingsboard/mqtt/broker/integration/service/api/DefaultIntegrationRateLimitService.java`
- Backpressure: Configurable ack/submit strategies in `integration/executor/src/main/java/org/thingsboard/mqtt/broker/integration/service/processing/backpressure/`

## Monitoring & Observability

**Metrics:**
- Spring Boot Actuator (`spring-boot-starter-actuator` in `common/stats/pom.xml`)
- Micrometer Core + Prometheus Registry (`micrometer-core`, `micrometer-registry-prometheus`)
- Endpoints exposed: `health,info,prometheus` (configurable via `METRICS_ENDPOINTS_EXPOSE`)
- Custom stats printing: `STATS_ENABLED` (default true), interval 60s
- Kafka consumer lag stats: `TB_KAFKA_CONSUMER_STATS_ENABLED` (default true)
- Application processor stats: `APPLICATION_PROCESSOR_STATS_ENABLED` (default true)
- Cache stats logging: `CACHE_STATS_ENABLED` (default true)

**Health Checks:**
- Actuator health endpoint with configurable detail level (`HEALTH_SHOW_DETAILS`)
- Disk space check: `HEALTH_DISKSPACE_ENABLED` (default false)

**System Info:**
- `com.github.oshi:oshi-core` 6.6.0 - CPU/memory monitoring
- Persist frequency: 60s (`STATS_SYSTEM_INFO_PERSIST_FREQUENCY_SEC`)
- Historical data reporting: configurable interval, persisted to time series

**Logging:**
- SLF4J + Logback (logback-core 1.5.25, logback-classic 1.5.25)
- Log4j bridged to SLF4J via `log4j-over-slf4j`
- Custom analysis logging for specific client IDs: `ANALYSIS_LOG_CLIENT_IDS`

**Error Tracking:**
- No external error tracking service (Sentry, etc.) detected
- Errors persisted to `error_event` table (partitioned, with TTL)
- Lifecycle events persisted to `lc_event` table
- Statistics events persisted to `stats_event` table

## Email

**Mail Server:**
- `com.sun.mail:jakarta.mail` 2.0.1
- Freemarker templates for email content (`spring-boot-starter-freemarker`)
- Mail thread pool: `ACTORS_RULE_MAIL_THREAD_POOL_SIZE` (default 4)
- Password reset mail pool: `ACTORS_RULE_MAIL_PASSWORD_RESET_THREAD_POOL_SIZE` (default 4)

## API Documentation

**Swagger/OpenAPI:**
- `org.thingsboard:springdoc-openapi-starter-webmvc-ui` 2.8.8TB (ThingsBoard fork)
- `io.swagger.core.v3:swagger-annotations-jakarta` 2.2.30
- Enabled: `SWAGGER_ENABLED` (default true)
- Path: `/api/**`
- UI title: "TBMQ REST API"

## CI/CD & Deployment

**GitHub Workflows** (`.github/workflows/`):
- `close-inactive-issues.yml` - Auto-close stale issues
- `license-header-format.yml` - License header enforcement
- `comment-on-wip-removal.yml` - PR workflow automation
- No build/test CI pipeline detected in the repository (likely external or private)

**Docker:**
- Broker Dockerfile: `msa/tbmq/docker/Dockerfile` and `msa/mqtt-broker/docker/Dockerfile`
- Integration Executor Dockerfile: `msa/integration/executor/docker/Dockerfile`
- Single-node compose: `msa/tbmq/configs/docker-compose.yml` (Postgres + Kafka + Valkey + TBMQ + Integration Executor)
- Cluster compose: `docker/docker-compose.yml` (2x TBMQ + 2x IE + HAProxy load balancer)
- Cache variants: `docker/cache/docker-compose.valkey.yml`, `docker/cache/docker-compose.valkey-cluster.yml`, `docker/cache/docker-compose.valkey-sentinel.yml`

**Kubernetes:**
- AWS: `k8s/aws/` (namespace, configmaps, deployments, load balancer recipes)
- GCP: `k8s/gcp/`
- Azure: `k8s/azure/`
- Minikube: `k8s/minikube/`
- Helm: `k8s/helm/aws/`

**Packaging:**
- Deb packages via Gradle packaging plugin (invoked from Maven)
- RPM packages via Gradle packaging plugin
- Windows service wrapper: `com.sun.winsw:winsw` 2.0.1

## Load Balancing

**HAProxy:**
- Image: `thingsboard/haproxy-certbot:2.2.33-alpine`
- Ports: 80 (HTTP), 443 (HTTPS), 1883 (MQTT), 8084 (MQTT WS), 8883 (MQTTS), 8085 (MQTT WSS)
- Let's Encrypt integration for TLS certificates
- Defined in `docker/docker-compose.yml`

## Network Protocols

**MQTT Listeners (Netty-based):**
| Listener | Default Port | Env Enable | Netty Workers |
|----------|-------------|------------|---------------|
| TCP | 1883 | `LISTENER_TCP_ENABLED` (true) | 12 |
| SSL/TLS | 8883 | `LISTENER_SSL_ENABLED` (false) | 12 |
| WebSocket | 8084 | `LISTENER_WS_ENABLED` (true) | 12 |
| Secure WebSocket | 8085 | `LISTENER_WSS_ENABLED` (false) | 12 |

- Max payload: 65536 bytes (per listener, configurable)
- PROXY protocol support: `MQTT_PROXY_PROTOCOL_ENABLED` (global) or per-listener
- WebSocket subprotocols: `mqttv3.1,mqtt`

**HTTP REST API:**
- Port: 8083 (`HTTP_BIND_PORT`)
- SSL: configurable (`SSL_ENABLED`, PEM or Keystore)
- HTTP/2: enabled by default when SSL is on
- CORS: enabled for `/api/**` with `*` origins by default

## Environment Configuration

**Required env vars for production:**
- `SPRING_DATASOURCE_URL` - PostgreSQL connection string
- `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` - DB credentials
- `TB_KAFKA_SERVERS` - Kafka bootstrap servers
- `REDIS_HOST` / `REDIS_PORT` - Redis/Valkey connection
- `TB_SERVICE_ID` - Unique broker node identifier

**Security-related env vars:**
- `JWT_TOKEN_SIGNING_KEY` - JWT signing key (base64 encoded)
- `REDIS_PASSWORD` - Redis password (optional)
- `REDIS_USERNAME` - Redis ACL username (optional)
- `SSL_*` - HTTP SSL configuration
- `LISTENER_SSL_*` - MQTT TLS configuration

**Secrets location:**
- Environment variables (passed via Docker env files or Kubernetes configmaps/secrets)
- Docker env files: `docker/tbmq.env`, `docker/kafka.env`, `docker/tbmq-integration-executor.env`
- Kubernetes configmaps: `k8s/*/tbmq-configmap.yml`, `k8s/*/tbmq-db-configmap.yml`, `k8s/*/tbmq-cache-configmap.yml`

## Webhooks & Callbacks

**Incoming:**
- HTTP MQTT Auth Provider: External HTTP service can authenticate MQTT clients via callback
  - Implementation: `application/src/main/java/org/thingsboard/mqtt/broker/service/auth/providers/http/HttpMqttClientAuthProvider.java`
  - Configuration: `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/security/http/HttpMqttAuthProviderConfiguration.java`
  - Connection check timeout: `INTEGRATIONS_INIT_CONNECTION_CHECK_API_REQUEST_TIMEOUT_SEC` (20s)

**Outgoing:**
- HTTP Integration: forwards MQTT messages to external HTTP endpoints
  - Implementation: `integration/executor/src/main/java/org/thingsboard/mqtt/broker/integration/service/integration/http/HttpIntegration.java`
- MQTT Integration: republishes messages to external MQTT brokers
  - Implementation: `integration/executor/src/main/java/org/thingsboard/mqtt/broker/integration/service/integration/mqtt/MqttIntegration.java`
  - Uses `org.thingsboard:netty-mqtt` 3.9.0 client library
- Kafka Integration: forwards messages to external Kafka topics
  - Implementation: `integration/executor/src/main/java/org/thingsboard/mqtt/broker/integration/service/integration/kafka/KafkaIntegration.java`

## ThingsBoard Dependencies

**ThingsBoard Artifacts (from `repo.thingsboard.io`):**
- `org.thingsboard:netty-mqtt` 3.9.0 - Netty-based MQTT client (used by integration executor for MQTT integrations and in tests)
- `org.thingsboard:springdoc-openapi-starter-webmvc-ui` 2.8.8TB - Customized Swagger UI
- `org.thingsboard:gradle-maven-plugin` - Gradle invocation from Maven for packaging

Repository: `https://repo.thingsboard.io/artifactory/libs-release-public`

---

*Integration audit: 2026-03-26*
