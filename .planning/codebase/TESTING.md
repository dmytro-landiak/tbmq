# Testing Patterns

**Analysis Date:** 2026-03-26

## Test Framework

**Runner:**
- JUnit 4 (primary, used throughout via `junit-vintage-engine` bridge)
- Annotations: `@Test`, `@Before`, `@After`, `@BeforeClass`, `@ClassRule`, `@RunWith`
- Spring Test: `@RunWith(SpringRunner.class)`, `@SpringBootTest`, `@ActiveProfiles("test")`
- Classpath Suite: `@RunWith(ClasspathSuite.class)` for aggregating tests into suites

**Assertion Libraries:**
- JUnit 4 `Assert` (primary): `Assert.assertNotNull`, `Assert.assertEquals`, `Assert.assertTrue`
- AssertJ: `assertThat(...)` from `org.assertj.core.api.Assertions`
- Spring MockMvc matchers: `status().isOk()`, `jsonPath(...)` for REST API tests

**Mocking:**
- Mockito 5.18.0
- Spring Boot annotations: `@MockitoBean`, `@MockitoSpyBean`
- Direct `mock()`, `when()`, `verify()` from Mockito

**Async Testing:**
- Awaitility: `Awaitility.await().atMost(10, TimeUnit.SECONDS).until(...)`
- ConcurrentUnit: `net.jodah.concurrentunit` for concurrent test assertions

**Run Commands:**
```bash
# Run all tests (unit + integration in application module)
mvn test

# Run only DAO module tests
mvn test -pl dao

# Run only application module tests (excludes sql/* tests by default)
mvn test -pl application

# Skip tests during build
mvn package -DskipTests
```

## Test File Organization

**Location:** Tests follow standard Maven convention -- co-located under `src/test/java/` in each module, mirroring the main source package structure.

**Naming:**
- Unit tests: `*Test.java` (e.g., `MqttPublishHandlerTest.java`, `ExponentialBackoffPolicyTest.java`)
- Integration test cases (application): `*IntegrationTestCase.java` (e.g., `Mqtt5IntegrationTestCase.java`, `AuthorizationIntegrationTestCase.java`)
- Service tests (DAO): `*ServiceTest.java` (e.g., `MqttClientCredentialsServiceTest.java`, `UserServiceTest.java`)
- Controller tests: `*ControllerTest.java` (e.g., `MqttClientCredentialsControllerTest.java`)
- Test suites: `*TestSuite.java` (e.g., `DaoServiceTestSuite.java`, `IntegrationTestSuite.java`)

**Test Count (approximate):**
- `application/src/test/`: ~161 test files
- `dao/src/test/`: ~24 test files
- Other modules: ~22 test files
- **Total:** ~207 test files

**Structure:**
```
application/src/test/java/org/thingsboard/mqtt/broker/
    AbstractPubSubIntegrationTest.java          # Base for all integration tests
    controller/
        AbstractControllerTest.java             # Base for controller tests
        AbstractWebTest.java                    # MockMvc infrastructure
        MqttClientCredentialsControllerTest.java
        MqttAuthProviderControllerTest.java
        IntegrationControllerTest.java
    actors/client/service/handlers/
        MqttPublishHandlerTest.java             # Unit tests with mocks
        MqttSubscribeHandlerTest.java
        ...
    service/testing/integration/
        IntegrationTestSuite.java               # Suite aggregator
        Mqtt5IntegrationTestCase.java           # Full integration tests
        AuthorizationIntegrationTestCase.java
        parent/
            AbstractFlowControlIntegrationTestCase.java
            ...
    service/mqtt/...                            # Unit tests for services

dao/src/test/java/org/thingsboard/mqtt/broker/dao/
    DaoServiceTestSuite.java                    # Suite aggregator (Redis standalone)
    DaoRedisClusterServiceTestSuite.java        # Suite aggregator (Redis cluster)
    AbstractRedisContainer.java                 # TestContainers Redis base
    AbstractRedisClusterContainer.java          # TestContainers Redis Cluster base
    PostgreSqlInitializer.java                  # DB init for TestContainers
    DaoSqlTest.java                             # Composite test annotation
    service/
        AbstractServiceTest.java                # Base for DAO service tests
        MqttClientCredentialsServiceTest.java
        UserServiceTest.java
        ...
```

## Test Types

### Unit Tests

**Scope:** Single class behavior with all dependencies mocked.

**Pattern:** Use `@RunWith(SpringRunner.class)` + `@ContextConfiguration(classes = ClassUnderTest.class)` for lightweight Spring context.

**Location:** Throughout `application/src/test/java/` in matching package structure.

**Example** (`application/src/test/java/org/thingsboard/mqtt/broker/actors/client/service/handlers/MqttPublishHandlerTest.java`):
```java
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = MqttPublishHandler.class)
public class MqttPublishHandlerTest {

    @MockitoBean
    MqttMessageGenerator mqttMessageGenerator;
    @MockitoBean
    MsgDispatcherService msgDispatcherService;

    @MockitoSpyBean
    MqttPublishHandler mqttPublishHandler;

    ClientSessionCtx ctx;

    @Before
    public void setUp() {
        ctx = mock(ClientSessionCtx.class);
        // setup mock behavior
    }

    @Test
    public void givenCondition_whenAction_thenResult() {
        // arrange, act, assert
    }
}
```

### DAO / Service Integration Tests

**Scope:** Service layer tests running against real PostgreSQL (via TestContainers) and Valkey/Redis.

**Test base class:** `AbstractServiceTest` at `dao/src/test/java/org/thingsboard/mqtt/broker/dao/service/AbstractServiceTest.java`

**Annotations:**
```java
@DaoSqlTest  // Composite: loads application-test.properties + sql-test.properties
public class MqttClientCredentialsServiceTest extends AbstractServiceTest {
    @Autowired
    private MqttClientCredentialsService mqttClientCredentialsService;

    @Before
    public void setUp() { /* cache setup */ }

    @After
    public void tearDown() { /* cleanup created data */ }

    @Test
    public void testSaveAndFindCredentials() { ... }
}
```

**Suite-based execution:** Surefire only runs `*TestSuite.java` in the DAO module. Test suites aggregate via classpath scan:
```java
@RunWith(ClasspathSuite.class)
@ClassnameFilters({"org.thingsboard.mqtt.broker.dao.*Test"})
public class DaoServiceTestSuite extends AbstractRedisContainer { }
```

### Full Integration Tests (Application)

**Scope:** Full Spring Boot context with real Kafka, Valkey, and PostgreSQL. Tests actual MQTT pub/sub flows using Paho MQTT clients.

**Test base class:** `AbstractPubSubIntegrationTest` at `application/src/test/java/org/thingsboard/mqtt/broker/AbstractPubSubIntegrationTest.java`

**Naming convention:** `*IntegrationTestCase.java` (important: the classpath suite filters for `*TestCase`)

**Suite:** `IntegrationTestSuite` at `application/src/test/java/org/thingsboard/mqtt/broker/service/testing/integration/IntegrationTestSuite.java`:
```java
@RunWith(ClasspathSuite.class)
@ClasspathSuite.ClassnameFilters({
        "org.thingsboard.mqtt.broker.service.testing.integration.*TestCase",
})
public class IntegrationTestSuite { }
```

**Surefire configuration** in `application/pom.xml`:
- Excludes `**/sql/*Test.java`
- Includes `**/*Test.java` and `**/*TestSuite.java`

**Example** (from `application/src/test/java/org/thingsboard/mqtt/broker/service/testing/integration/ResponseInfoIntegrationTestCase.java`):
```java
@Slf4j
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ContextConfiguration(classes = ResponseInfoIntegrationTestCase.class, loader = SpringBootContextLoader.class)
@TestPropertySource(properties = {
        "mqtt.response-info=test/"
})
@DaoSqlTest
@RunWith(SpringRunner.class)
public class ResponseInfoIntegrationTestCase extends AbstractPubSubIntegrationTest {

    @Test
    public void givenRequestResponseInfoIsTrue_whenProcessingConnect_thenResponseInfoValueIsReturned() throws Throwable {
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setRequestResponseInfo(true);

        MqttClient client = new MqttClient(SERVER_URI + mqttPort, "responseInfoClient");
        IMqttToken iMqttToken = client.connectWithResult(options);

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .until(client::isConnected);

        MqttProperties responseProperties = iMqttToken.getResponseProperties();
        Assert.assertEquals("test/", responseProperties.getResponseInfo());

        client.disconnect();
        client.close();
    }
}
```

### Controller (REST API) Tests

**Scope:** Full Spring Boot context with MockMvc for HTTP request/response testing.

**Test base hierarchy:**
1. `AbstractPubSubIntegrationTest` -- TestContainers (Kafka, Valkey, PostgreSQL)
2. `AbstractWebTest` at `application/src/test/java/org/thingsboard/mqtt/broker/controller/AbstractWebTest.java` -- MockMvc setup, auth helpers (`loginSysAdmin()`), HTTP helpers (`doPost`, `doGet`, `doDelete`, `doGetTypedWithPageLink`)
3. `AbstractControllerTest` at `application/src/test/java/org/thingsboard/mqtt/broker/controller/AbstractControllerTest.java` -- `@SpringBootTest` + `@DirtiesContext`

**Example** (`application/src/test/java/org/thingsboard/mqtt/broker/controller/MqttClientCredentialsControllerTest.java`):
```java
@Slf4j
@DaoSqlTest
public class MqttClientCredentialsControllerTest extends AbstractControllerTest {

    @Before
    public void beforeTest() throws Exception {
        loginSysAdmin();
    }

    @After
    public void afterTest() throws Exception {
        loginSysAdmin();
        // cleanup all created credentials
    }

    @Test
    public void saveBasicMqttClientCredentialsTest() throws Exception {
        MqttClientCredentials saved = doPost("/api/mqtt/client/credentials", credentials, MqttClientCredentials.class);
        Assert.assertNotNull(saved);
        Assert.assertNotNull(saved.getId());
    }
}
```

## Test Infrastructure

### TestContainers

**PostgreSQL:**
- Used via JDBC URL in `dao/src/test/resources/sql-test.properties`
- URL: `jdbc:tc:postgresql:17:///thingsboard_mqtt_broker?TC_DAEMON=true&TC_TMPFS=/testtmpfs:rw&?TC_INITFUNCTION=org.thingsboard.mqtt.broker.dao.PostgreSqlInitializer::initDb`
- Driver: `org.testcontainers.jdbc.ContainerDatabaseDriver`
- DB init: `PostgreSqlInitializer.initDb()` at `dao/src/test/java/org/thingsboard/mqtt/broker/dao/PostgreSqlInitializer.java` runs SQL schema files

**Kafka:**
- Image: `confluentinc/cp-kafka:7.9.2`
- Managed via `@ClassRule` in `AbstractPubSubIntegrationTest`
- Kafka bootstrap servers injected via `ReplaceKafkaPropertiesBeanPostProcessor` (BeanPostProcessor that replaces `TbKafkaConsumerSettings`, `TbKafkaProducerSettings`, `TbKafkaAdminSettings`)

**Valkey (Redis-compatible):**
- Image: `valkey/valkey:8.0`
- Standalone mode: `AbstractRedisContainer` at `dao/src/test/java/org/thingsboard/mqtt/broker/dao/AbstractRedisContainer.java`
- Cluster mode (6 nodes): `AbstractRedisClusterContainer` at `dao/src/test/java/org/thingsboard/mqtt/broker/dao/AbstractRedisClusterContainer.java`
- Properties injected via `System.setProperty()` in `@ClassRule` `ExternalResource`

**WireMock:**
- Dependency: `wiremock-testcontainers-module` v1.0-alpha-13
- Used for HTTP auth provider testing

### MQTT Clients (for integration tests)

- **Eclipse Paho MQTT v3**: `org.eclipse.paho.client.mqttv3` (v1.2.5)
- **Eclipse Paho MQTT v5**: `org.eclipse.paho.mqttv5.client`
- **HiveMQ MQTT Client**: `com.hivemq:hivemq-mqtt-client` (v1.3.3)
- **ThingsBoard Netty MQTT**: `org.thingsboard:netty-mqtt`

Tests connect to the broker's TCP port (`listener.tcp.bind_port`) which runs on `localhost` with a dynamic port in tests.

### Test Profiles & Configuration

**Active profile:** `test` -- activated via `@ActiveProfiles("test")` on base test classes

**Test configuration files:**
- `dao/src/test/resources/application-test.properties` -- cache settings, Lettuce config, DB settings
- `dao/src/test/resources/sql-test.properties` -- PostgreSQL TestContainers JDBC URL, JPA settings
- `application/src/test/resources/application-test.properties` -- minimal overrides (`database.ts_max_intervals`, `stats.print-interval-ms`)

**System properties set in tests:**
- `tbmq.graceful.shutdown.timeout.sec=0` (in `AbstractPubSubIntegrationTest.setup()`)
- `spring.config.name=thingsboard-mqtt-broker` (via Surefire systemPropertyVariables)

## Mocking

**Framework:** Mockito 5.18.0

**Patterns:**

**Spring-integrated mocking (unit tests with minimal context):**
```java
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = MqttPublishHandler.class)
public class MqttPublishHandlerTest {

    @MockitoBean
    MqttMessageGenerator mqttMessageGenerator;

    @MockitoSpyBean
    MqttPublishHandler mqttPublishHandler;  // class under test as spy
}
```

**Direct mocking (for non-Spring objects):**
```java
@Before
public void setUp() {
    ctx = mock(ClientSessionCtx.class);
    actorRef = mock(TbActorRef.class);
    when(ctx.getChannel()).thenReturn(channelHandlerContext);
}
```

**What to mock:**
- All service dependencies in unit tests
- Netty `ChannelHandlerContext` and channel objects
- Kafka infrastructure (replaced via `BeanPostProcessor` in integration tests)

**What NOT to mock:**
- In integration tests: database, Kafka, Redis -- use real TestContainers
- The class under test itself (use `@MockitoSpyBean` if you need partial mocking)

## Fixtures and Factories

**Test Data:**
- Helper methods in test classes (e.g., `newBasicMqttCredentials()`, `newSslMqttCredentials()`)
- No separate fixture/factory classes
- `AbstractServiceTest` provides `generateEvent()` and `readFromResource()` utility methods
- `AbstractWebTest` provides `loginSysAdmin()` and typed HTTP request helpers
- `AbstractPubSubIntegrationTest` provides MQTT connection helpers (`getOptions()`)

**Common test constants:**
```java
public static final String LOCALHOST = "localhost";
public static final String SERVER_URI = "tcp://" + LOCALHOST + ":";
public static final byte[] PAYLOAD = "testPayload".getBytes(StandardCharsets.UTF_8);
```

**Test data cleanup:**
- `@After` methods responsible for cleaning up created test data
- Pattern: query all created entities, then delete them one by one
- `@Before` in `AbstractPubSubIntegrationTest` resets MQTT auth providers to default configuration

## Coverage

**Requirements:** Not formally enforced. No Jacoco or coverage threshold configuration detected in POM files.

**View Coverage:**
```bash
# No built-in coverage command; use IDE or add Jacoco manually
mvn test -pl application  # Run tests only
```

## Test Method Naming

**Convention observed:**
- `test` + descriptive name: `testSaveAndFindCredentials`, `testEmptyClientWithNoCleanSession`
- `given_when_then` style (newer tests): `givenRequestResponseInfoIsTrue_whenProcessingConnect_thenResponseInfoValueIsReturned`
- Short descriptive names: `saveBasicMqttClientCredentialsTest`, `deleteCredentialsTest`

Use the `given_when_then` style for new tests.

## Common Patterns

**Async Testing:**
```java
Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .until(client::isConnected);
```

**Error Testing:**
```java
@Test(expected = MqttException.class)
public void testEmptyClientWithNoCleanSession() throws Throwable {
    // ...
    throw e;
}
```

**REST API Testing:**
```java
MqttClientCredentials saved = doPost("/api/mqtt/client/credentials", credentials, MqttClientCredentials.class);
doDelete("/api/mqtt/client/credentials/" + saved.getId()).andExpect(status().isOk());
PageData<MqttClientCredentials> page = doGetTypedWithPageLink("/api/mqtt/client/credentials?",
        new TypeReference<>() {}, new PageLink(10_000));
```

**MQTT Pub/Sub Testing:**
```java
MqttClient client = new MqttClient(SERVER_URI + mqttPort, "clientId");
client.connect(options);
Awaitility.await().atMost(10, TimeUnit.SECONDS).until(client::isConnected);
// perform assertions
client.disconnect();
client.close();
```

## UI Testing

**Approach:** No UI tests detected. The `ui-ngx/` module contains Angular frontend code but no `*.spec.ts` test files are present.

## Black-Box Tests

**Location:** `msa/black-box-tests/`
**Status:** Skipped by default (`blackBoxTests.skip=true` in POM)
**Purpose:** External system-level tests against a running TBMQ instance
**Dependencies:** Paho MQTT v3, TestContainers, ConcurrentUnit
**Not part of regular test cycle** -- must be enabled explicitly

---

*Testing analysis: 2026-03-26*
