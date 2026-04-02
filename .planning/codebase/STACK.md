# Technology Stack

**Analysis Date:** 2026-03-26

## Languages

**Primary:**
- Java 17 - Backend (MQTT broker, DAO, queue, integration executor). Configured via `maven.compiler.source`/`target` in `pom.xml`.
- TypeScript ~5.5.4 - Frontend Angular UI in `ui-ngx/`.

**Secondary:**
- SQL (PostgreSQL dialect) - Database schema and migrations in `dao/src/main/resources/sql/`.
- Protocol Buffers - Inter-service messaging schemas in `common/queue/src/main/proto/queue.proto` and `common/queue/src/main/proto/integration.proto`.
- SCSS - UI component styles (configured in `ui-ngx/angular.json`).

## Runtime

**Environment:**
- JDK 17 (OpenJDK) - Docker base image: `thingsboard/openjdk17:bookworm-slim` (see `msa/tbmq/docker/Dockerfile`, `msa/mqtt-broker/docker/Dockerfile`, `msa/integration/executor/docker/Dockerfile`).
- Node.js - Required for frontend build (`ui-ngx/`). Uses `--max_old_space_size=8048` for dev and `4096` for production builds.

**Package Manager:**
- Maven - Backend build tool. Root `pom.xml` defines multi-module reactor build.
- npm - Frontend package management. Lockfile: `ui-ngx/package-lock.json` (check existence).
- Gradle - Used for native packaging (deb/rpm) via `gradle-maven-plugin` invoked from Maven.

## Frameworks

**Core:**
- Spring Boot 3.4.13 - Application framework. Defined in `pom.xml` as `spring-boot.version`. Starters used: `spring-boot-starter-web`, `spring-boot-starter-security`, `spring-boot-starter-websocket`, `spring-boot-starter-webflux`, `spring-boot-starter-data-jpa`, `spring-boot-starter-freemarker`, `spring-boot-starter-actuator`.
- Angular 19.0.6 - Frontend SPA framework. See `ui-ngx/package.json`.
- Netty (via `netty-all`, `netty-handler`, `netty-codec-mqtt`, `netty-transport-native-epoll`) - High-performance MQTT TCP/SSL/WS/WSS listeners. Core network I/O layer.

**Testing:**
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

**Build/Dev:**
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

**Critical (Backend):**
- `org.apache.kafka:kafka-clients` 3.9.1 - Apache Kafka client for all inter-broker and message queue communication.
- `io.netty:netty-codec-mqtt` (managed via Spring Boot BOM) - MQTT protocol codec for Netty pipeline.
- `com.google.protobuf:protobuf-java` 3.25.5 - Serialization for Kafka messages between services.
- `org.postgresql:postgresql` (managed via Spring Boot BOM) - PostgreSQL JDBC driver.
- `redis.clients:jedis` 5.1.5 - Redis/Valkey client (Jedis mode).
- `io.lettuce:lettuce-core` 6.5.1.RELEASE - Redis/Valkey client (Lettuce mode, preferred for reactive).
- `org.springframework.data:spring-data-redis` - Spring Data Redis abstraction.
- `com.bucket4j:bucket4j_jdk17-core` 8.13.0 - Token-bucket rate limiting (also `bucket4j_jdk17-jedis`, `bucket4j_jdk17-redis-common`).

**Security:**
- `io.jsonwebtoken:jjwt` 0.12.5 - JWT token generation/validation for REST API.
- `com.nimbusds:nimbus-jose-jwt` 10.3 - JWT MQTT auth provider support.
- `com.google.crypto.tink:tink` 1.11.0 - Google crypto library for JWT MQTT auth.
- `org.bouncycastle:bcprov-jdk18on` + `bcpkix-jdk18on` 1.79 - X.509 certificate handling for TLS/SSL MQTT auth.
- `org.passay:passay` 1.6.4 - Password validation rules.
- `org.owasp.antisamy:antisamy` 1.7.5 - HTML sanitization/XSS prevention.
- `org.springframework.boot:spring-boot-starter-security` - Spring Security for REST API auth.

**Infrastructure:**
- `org.projectlombok:lombok` 1.18.38 - Boilerplate code reduction (provided scope).
- `com.google.guava:guava` 33.1.0-jre - Utilities and collections.
- `org.apache.commons:commons-lang3` 3.18.0 - String/object utilities.
- `com.fasterxml.jackson` 2.18.6 - JSON serialization (managed via Jackson BOM).
- `com.github.oshi:oshi-core` 6.6.0 - System info (CPU/memory monitoring).
- `ch.qos.logback:logback-classic` 1.5.25 - Logging implementation.
- `com.sun.mail:jakarta.mail` 2.0.1 - Email sending.

**Critical (Frontend):**
- `@angular/material` 19.0.5 - UI component library.
- `@ngrx/store` 18.1.1 + `@ngrx/effects` 18.1.1 - State management.
- `rxjs` 7.8.1 - Reactive programming.
- `mqtt` 5.3.0 - MQTT.js client for WebSocket MQTT client in browser.
- `chart.js` 4.4.0 + plugins - Dashboard charting.
- `ace-builds` 1.36.2 - Code editor.
- `@ngx-translate/core` 15.0.0 - i18n support.

**API Documentation:**
- `org.thingsboard:springdoc-openapi-starter-webmvc-ui` 2.8.8TB (ThingsBoard custom fork) - Swagger/OpenAPI UI.
- `io.swagger.core.v3:swagger-annotations-jakarta` 2.2.30 - API annotations.

## Configuration

**Environment:**
- All configuration is externalized via environment variables with defaults in `application/src/main/resources/thingsboard-mqtt-broker.yml`.
- Pattern: `"${ENV_VAR_NAME:default_value}"` throughout the YAML.
- Docker env files exist at `docker/tbmq.env`, `docker/kafka.env`, `docker/tbmq-integration-executor.env`, `docker/cache-valkey.env`, `docker/cache-valkey-cluster.env`, `docker/cache-valkey-sentinel.env`.
- i18n messages: `application/src/main/resources/i18n/messages.properties`.

**Build:**
- `pom.xml` - Root Maven POM with all version properties and dependency management.
- `application/pom.xml` - Main broker application.
- `integration/executor/pom.xml` - Integration executor application.
- `ui-ngx/angular.json` - Angular build configuration.
- `ui-ngx/tsconfig.json` - TypeScript compiler configuration (target: ES2022, module: es2020).

## Maven Modules

```
mqtt-broker (root)
  ├── application           # Main TBMQ broker Spring Boot app
  ├── common/
  │   ├── actor             # Actor system framework
  │   ├── cache             # Redis/Valkey cache layer
  │   ├── dao-api           # DAO interfaces
  │   ├── data              # Domain model / DTOs
  │   ├── integration/
  │   │   ├── integration-api         # Integration API interfaces
  │   │   └── cluster-integration-api # Cluster integration API
  │   ├── queue             # Kafka queue abstractions + protobuf
  │   ├── stats             # Metrics/monitoring (Micrometer + Prometheus)
  │   └── util              # Shared utilities
  ├── dao                   # JPA/SQL data access layer
  ├── integration/
  │   └── executor          # Integration executor Spring Boot app
  ├── msa/
  │   ├── black-box-tests   # End-to-end tests
  │   ├── integration       # Docker integration tests
  │   ├── mqtt-broker       # Docker packaging for broker
  │   └── tbmq              # Docker packaging for single-node setup
  └── ui-ngx                # Angular frontend
```

## Platform Requirements

**Development:**
- JDK 17+
- Maven 3.6+
- Node.js (for `ui-ngx` build)
- Docker (for integration tests via Testcontainers)
- PostgreSQL 17 (or use Testcontainers)
- Apache Kafka 4.0.0 (or use Testcontainers)
- Valkey/Redis 8.0 (for caching/rate limiting)

**Production:**
- Docker with `thingsboard/openjdk17:bookworm-slim` base image
- PostgreSQL 17
- Apache Kafka 4.0.0 (KRaft mode, no ZooKeeper)
- Valkey 8.0 (standalone, sentinel, or cluster)
- HAProxy (for load balancing in cluster mode) - image `thingsboard/haproxy-certbot:2.2.33-alpine`
- Ports: 1883 (MQTT TCP), 8083 (HTTP/REST API), 8084 (MQTT WebSocket), 8883 (MQTTS), 8085 (MQTT WSS)

**Packaging:**
- Deb packages built via Gradle packaging plugin
- Docker images: `thingsboard/tbmq`, `thingsboard/tbmq-integration-executor`
- Kubernetes: Helm charts and manifests for AWS, GCP, Azure, Minikube in `k8s/`

---

*Stack analysis: 2026-03-26*
