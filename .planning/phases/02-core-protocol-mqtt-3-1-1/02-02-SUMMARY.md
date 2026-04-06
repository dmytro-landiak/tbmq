---
phase: 02-core-protocol-mqtt-3-1-1
plan: 02
subsystem: testing
tags: [mqtt, paho, integration-tests, test-scaffold, disabled-stubs]

# Dependency graph
requires:
  - phase: 01-foundation
    provides: MqttTcpServerBootstrap with getLocalPort() for test port discovery
provides:
  - Eclipse Paho MQTTv3 1.2.5 test dependency in lightweight/pom.xml
  - Awaitility test dependency for async assertions
  - AbstractMqttIntegrationTest base class with random-port SpringBootTest setup
  - 8 test classes with 30 @Disabled stubs covering all Phase 2 PROTO requirements
affects: [02-03, 02-04, 02-05]

# Tech tracking
tech-stack:
  added:
    - Eclipse Paho MQTTv3 1.2.5 (test scope) — MQTT client for integration tests
    - Awaitility (test scope, Spring Boot BOM managed) — async test assertions
  patterns:
    - AbstractMqttIntegrationTest pattern: abstract SpringBootTest base class with random port, client factory, and @AfterEach cleanup
    - @Disabled("Enabled in Plan XX") convention for test-first scaffolding

key-files:
  created:
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/AbstractMqttIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttConnectIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttQosIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttSubscribeIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttKeepAliveIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttRetainedMsgIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttLwtIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttSessionIntegrationTest.java
    - lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttClientTakeoverTest.java
  modified:
    - lightweight/pom.xml

key-decisions:
  - "Use @Disabled at method level (not class level) so Maven Surefire discovers all test classes but skips individual methods — gives a clear count of pending tests"
  - "Each @Disabled annotation includes the enabling plan number to create a clear ticket from scaffold to implementation"

patterns-established:
  - "AbstractMqttIntegrationTest: abstract base class with @SpringBootTest(port=0) + @DirtiesContext(AFTER_CLASS) + Paho client factory + @AfterEach cleanup"
  - "Disabled stubs include inline comments documenting exact expected behavior per MQTT spec section references"

requirements-completed:
  - PROTO-01
  - PROTO-02
  - PROTO-03
  - PROTO-04
  - PROTO-05
  - PROTO-06
  - PROTO-07
  - PROTO-11
  - TRAN-01

# Metrics
duration: 2min
completed: 2026-04-06
---

# Phase 2 Plan 02: MQTT Integration Test Scaffold Summary

**Eclipse Paho MQTTv3 dependency + AbstractMqttIntegrationTest base class + 30 @Disabled stubs covering all PROTO-01 through PROTO-11 and TRAN-01 requirements**

## Performance

- **Duration:** 2 min
- **Started:** 2026-04-06T12:49:26Z
- **Completed:** 2026-04-06T12:51:46Z
- **Tasks:** 2
- **Files modified:** 10

## Accomplishments

- Added Eclipse Paho MQTTv3 1.2.5 and Awaitility test dependencies to lightweight/pom.xml with Netty PARANOID leak detection in Surefire argLine
- Created AbstractMqttIntegrationTest providing a shared Spring Boot test base with random-port Netty server, Paho client factory, and automatic client cleanup after each test
- Created 8 test classes with 30 @Disabled("Enabled in Plan XX") stubs covering every Phase 2 PROTO requirement — each stub documents exact expected behaviors for Plans 03-05 to satisfy

## Task Commits

Each task was committed atomically:

1. **Task 1: Add Paho dependency and create AbstractMqttIntegrationTest base class** - `4c64c8208` (feat)
2. **Task 2: Create @Disabled test stubs for all Phase 2 requirements** - `c579741d9` (test)

**Plan metadata:** (see final commit in state updates)

## Files Created/Modified

- `lightweight/pom.xml` - Added Paho 1.2.5, Awaitility (test scope), PARANOID leak detection in Surefire argLine
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/AbstractMqttIntegrationTest.java` - Base class with @SpringBootTest(port=0), @DirtiesContext(AFTER_CLASS), brokerUrl(), createClient(), defaultConnectOptions(), @AfterEach cleanup
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttConnectIntegrationTest.java` - 5 stubs for PROTO-01 + TRAN-01 (enabled Plan 03)
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttQosIntegrationTest.java` - 5 stubs for PROTO-02 (enabled Plan 04)
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttSubscribeIntegrationTest.java` - 5 stubs for PROTO-03 including wildcards (enabled Plan 04)
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttKeepAliveIntegrationTest.java` - 3 stubs for PROTO-04 (enabled Plan 03)
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttRetainedMsgIntegrationTest.java` - 4 stubs for PROTO-05 (enabled Plan 05)
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttLwtIntegrationTest.java` - 3 stubs for PROTO-06 (enabled Plan 05)
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttSessionIntegrationTest.java` - 2 stubs for PROTO-07 (enabled Plan 03)
- `lightweight/src/test/java/org/thingsboard/mqtt/broker/lightweight/mqtt/MqttClientTakeoverTest.java` - 3 stubs for PROTO-11 (enabled Plan 05)

## Decisions Made

- Used `@Disabled` at method level rather than class level so Maven Surefire still discovers and counts all test methods — gives a measurable count of pending tests to implement
- Each `@Disabled` annotation includes the plan number that will enable it, creating a direct mapping from scaffold to implementation plan
- Used `@DirtiesContext(classMode = AFTER_CLASS)` rather than `AFTER_EACH_METHOD` to keep test execution fast while still isolating Spring contexts between test classes

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Test scaffold is complete; Plans 03-05 can now enable specific test classes as features are implemented
- Plan 03 (CONNECT handler) will enable: MqttConnectIntegrationTest, MqttKeepAliveIntegrationTest, MqttSessionIntegrationTest (10 tests total)
- Plan 04 (pub/sub dispatch) will enable: MqttQosIntegrationTest, MqttSubscribeIntegrationTest (10 tests total)
- Plan 05 (retained/LWT/takeover) will enable: MqttRetainedMsgIntegrationTest, MqttLwtIntegrationTest, MqttClientTakeoverTest (10 tests total)

---
*Phase: 02-core-protocol-mqtt-3-1-1*
*Completed: 2026-04-06*

## Self-Check: PASSED

- All 10 files confirmed present on disk
- Both task commits confirmed in git log (4c64c820, c579741d)
- Final metadata commit: 4dad26661
