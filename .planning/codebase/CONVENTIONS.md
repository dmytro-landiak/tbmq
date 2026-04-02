# Coding Conventions

**Analysis Date:** 2026-03-26

## License Header

Every Java source file MUST start with the Apache 2.0 license header. The template is at `/license-header-template.txt`:

```java
/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */
```

## Naming Patterns

**Packages:**
- Root package: `org.thingsboard.mqtt.broker`
- Use lowercase, dot-separated segments that mirror the module/layer purpose
- Examples: `service.mqtt.retain`, `actors.client.service.handlers`, `dao.model`

**Classes:**
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

**Methods:**
- camelCase
- Service methods: verb-first (e.g., `saveCredentials`, `findById`, `deleteCredentials`, `getCredentialsById`)
- Check/validation methods: `check` prefix (e.g., `checkNotNull`, `checkUserId`, `checkIntegrationId`)
- Factory methods: `newInstance` pattern (e.g., `RetainedMsgDto.newInstance(retainedMsg)`)

**Variables:**
- camelCase for instance/local variables
- `UPPER_SNAKE_CASE` for static final constants
- Constants for property names in `ModelConstants`: `ENTITY_NAME_PROPERTY_NAME_PROPERTY` pattern (e.g., `USER_EMAIL_PROPERTY`, `MQTT_CLIENT_CREDENTIALS_TYPE_PROPERTY`)

**Files:**
- Java files: PascalCase matching class name
- Config files: kebab-case YAML (`thingsboard-mqtt-broker.yml`)
- SQL files: kebab-case (`schema-entities.sql`, `drop-all-tables.sql`)
- Proto files: lowercase (`queue.proto`, `integration.proto`)

## Lombok Usage

Lombok is used extensively. Configuration in `/lombok.config`:
```
config.stopbubbling = true
lombok.anyconstructor.addconstructorproperties = false
lombok.copyableAnnotations += org.springframework.context.annotation.Lazy
```

**Common Lombok annotations:**
- `@Slf4j` -- on nearly all service classes and controllers for logging
- `@Data` -- on DTOs, entities, configuration beans
- `@Getter` / `@Setter` -- on domain model classes (e.g., `User`, `BaseData`)
- `@RequiredArgsConstructor` -- on service implementations for constructor injection
- `@EqualsAndHashCode(callSuper = true)` -- on entity/domain subclasses
- `@Builder` -- on test data objects and some message types
- `@AllArgsConstructor` / `@NoArgsConstructor` -- on builder-compatible classes

## Spring Patterns

### Service Layer (application module)

**Two-tier service pattern:**

1. **`Tb*Service`** interface + `DefaultTb*Service` implementation in `application/src/main/java/.../service/entity/`:
   - Business-logic-level services called by controllers
   - Interface: `TbMqttClientCredentialsService` at `application/src/main/java/org/thingsboard/mqtt/broker/service/entity/credentials/TbMqttClientCredentialsService.java`
   - Implementation: `DefaultTbMqttClientCredentialsService` at `application/src/main/java/org/thingsboard/mqtt/broker/service/entity/credentials/DefaultTbMqttClientCredentialsService.java`
   - Annotated with `@Service`, `@Slf4j`, `@RequiredArgsConstructor`
   - Extends `AbstractTbEntityService` at `application/src/main/java/org/thingsboard/mqtt/broker/service/entity/AbstractTbEntityService.java`

2. **`*Service`** interface + `*ServiceImpl` implementation in `dao/src/main/java/.../dao/`:
   - Data-access-level services
   - Interface: `MqttClientCredentialsService` at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/client/MqttClientCredentialsService.java`
   - Implementation: `MqttClientCredentialsServiceImpl` at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/client/MqttClientCredentialsServiceImpl.java`
   - Annotated with `@Service`, `@Slf4j`, `@RequiredArgsConstructor`

### Controller Pattern

- All controllers extend `BaseController` at `application/src/main/java/org/thingsboard/mqtt/broker/controller/BaseController.java`
- Annotated with `@RestController`, `@RequiredArgsConstructor`, `@RequestMapping("/api")`
- All endpoints secured with `@PreAuthorize("hasAuthority('SYS_ADMIN')")`
- Use `createPageLink()` from `BaseController` for pagination
- Error handling done centrally via `@ExceptionHandler` in `BaseController`
- Exception mapping: `ThingsboardException` with `ThingsboardErrorCode` enum

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class MqttClientCredentialsController extends BaseController {

    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @PostMapping(value = "/mqtt/client/credentials")
    public MqttClientCredentials saveMqttClientCredentials(@RequestBody MqttClientCredentials mqttClientCredentials) throws ThingsboardException {
        // ...
    }
}
```

### Dependency Injection

- Prefer `@RequiredArgsConstructor` with `private final` fields for constructor injection (in newer code)
- Older code uses `@Autowired` field injection (especially in `BaseController` and `AbstractTbEntityService`)
- For new code, use constructor injection via `@RequiredArgsConstructor`

### Configuration Pattern

**`@ConfigurationProperties` beans:**
```java
@Configuration
@ConfigurationProperties(prefix = "mqtt.rate-limits.incoming-publish")
@Data
public class IncomingRateLimitsConfiguration {
    private boolean enabled;
    private String clientConfig;
}
```
- Located in `application/src/main/java/org/thingsboard/mqtt/broker/config/`
- Use `@Configuration` + `@ConfigurationProperties(prefix = "...")` + `@Data`
- Some config uses `@Value("${property.name}")` for individual properties (seen in `BaseController`, `BrokerHomePageConfig`)

**YAML Configuration:**
- Main config: `application/src/main/resources/thingsboard-mqtt-broker.yml`
- All properties support environment variable overrides: `"${ENV_VAR:defaultValue}"`
- Pattern: `property_name: "${ENV_VAR_NAME:default_value}"`

## DAO / Data Access Layer

### Entity-Domain Mapping Pattern

**Domain (data) classes** in `common/data/src/main/java/`:
- Extend `BaseData` (which extends `IdBased`)
- Use Lombok `@Getter`/`@Setter`
- UUID-based IDs
- Located at `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/`

**JPA Entity classes** in `dao/src/main/java/.../dao/model/`:
- Extend `BaseSqlEntity<DomainClass>` at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/model/BaseSqlEntity.java`
- Implement `BaseEntity<DomainClass>` at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/model/BaseEntity.java`
- Provide constructor from domain class and `toData()` method for reverse mapping
- Use `ModelConstants` for column name references at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/model/ModelConstants.java`
- All column names use `snake_case` constants

```java
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = ModelConstants.USER_COLUMN_FAMILY_NAME)
public class UserEntity extends BaseSqlEntity<User> implements BaseEntity<User> {
    // Fields with @Column annotations using ModelConstants

    public UserEntity(User user) {
        // Map from domain to entity
    }

    @Override
    public User toData() {
        // Map from entity to domain
    }
}
```

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

**Exception Hierarchy:**
- `ThingsboardException` (checked) at `common/data/src/main/java/org/thingsboard/mqtt/broker/common/data/exception/ThingsboardException.java` -- primary exception with `ThingsboardErrorCode`
- `DataValidationException` (unchecked) at `common/data/src/main/java/org/thingsboard/mqtt/broker/exception/DataValidationException.java` -- for validation failures
- `IncorrectParameterException` (unchecked) at `dao/src/main/java/org/thingsboard/mqtt/broker/dao/exception/IncorrectParameterException.java` -- for invalid parameters
- `TbRateLimitsException` at `common/data/src/main/java/org/thingsboard/mqtt/broker/exception/TbRateLimitsException.java`

**Controller error handling:**
- `@ExceptionHandler` methods in `BaseController` catch and map all exceptions to `ThingsboardException`
- `ThingsboardErrorResponseHandler` at `application/src/main/java/org/thingsboard/mqtt/broker/exception/ThingsboardErrorResponseHandler.java` formats HTTP error responses
- Error codes defined in `ThingsboardErrorCode` enum: `GENERAL`, `AUTHENTICATION`, `BAD_REQUEST_PARAMS`, `ITEM_NOT_FOUND`, `DATABASE`

**Pattern:**
```java
// In controllers -- exceptions auto-handled by @ExceptionHandler
MqttClientCredentials credentials = checkClientCredentialsId(clientCredentialsId);

// In service layer -- throw DataValidationException for invalid data
if (mqttClientCredentials.getCredentialsType() == null) {
    throw new DataValidationException("MQTT Client credentials type should be specified");
}
```

## Logging

**Framework:** SLF4J via Lombok `@Slf4j` (backed by Logback)

**Patterns:**
- Use `@Slf4j` annotation on classes, then reference `log` field
- Use parameterized messages with `{}` placeholders (never string concatenation)
- Use `log.trace` for detailed debugging (protocol-level tracing)
- Use `log.debug` for operation-level debugging
- Use `log.info` for startup/lifecycle events
- Use `log.warn` for recoverable issues
- Use `log.error` for failures with exception parameter

```java
log.trace("[{}] Forwarding message to local MQTT authorization routing service {}", serviceId, notificationProto);
log.info("Started TBMQ in {} seconds", TimeUnit.MILLISECONDS.toSeconds(startupTimeMs));
log.error("Failed to start application.", e);
log.warn("[{}] Failed to send notification for broker node {}.", serviceId, notificationProto, t);
```

**Client-level logging:** A separate `ClientLogger` service at `application/src/main/java/org/thingsboard/mqtt/broker/service/analysis/ClientLogger.java` for MQTT client activity tracing.

## Import Organization

**Order (by convention observed in source files):**
1. `com.*` (third-party: Jackson, Google, etc.)
2. `io.*` (Netty, jsonwebtoken, etc.)
3. `jakarta.*`
4. `lombok.*`
5. `org.*` (Spring, JUnit, Thingsboard, etc.)
6. `java.*` standard library

**Within groups:** sorted alphabetically. No wildcard imports. Static imports at the end.

**Static imports:**
```java
import static org.thingsboard.mqtt.broker.dao.service.Validator.validateId;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
```

## Code Style

**Formatting:**
- 4-space indentation (Java)
- Opening brace on same line
- No explicit formatting tool configuration detected (no Checkstyle, no EditorConfig)
- Max line length is not formally enforced

**Constants:**
- Grouped by entity/feature area in constants classes (`BrokerConstants`, `ModelConstants`)
- Private constructor on utility/constants classes to prevent instantiation

**Custom Annotations:**
- `@AfterStartUp(order = N)` at `application/src/main/java/org/thingsboard/mqtt/broker/config/annotations/AfterStartUp.java` -- wraps `@EventListener(ApplicationReadyEvent.class)` + `@Order`
- `@DaoSqlTest` at `dao/src/test/java/org/thingsboard/mqtt/broker/dao/DaoSqlTest.java` -- composite annotation for DAO tests with SQL test properties

## Module Design

**Exports:**
- Each module is a Maven artifact; cross-module dependencies declared in POM
- `dao` module exports a test-jar for reuse in `application` module tests
- `common/data` module provides shared domain model classes
- `common/queue` module provides protobuf definitions and Kafka abstractions

**Barrel Files:** Not applicable (Java uses package-based organization)

---

*Convention analysis: 2026-03-26*
