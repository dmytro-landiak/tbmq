# Milestones

## v1.0 TBMQ Lightweight v1.0 (Shipped: 2026-05-07)

**Phases completed:** 7 phases, 21 plans, 35 tasks

**Key accomplishments:**

- Spring Boot 3.5.3 application with RocksDB 9.7.4 embedded storage using SmartLifecycle Integer.MIN_VALUE phase ordering and four column families (credentials, acl_rules, retained_messages, metadata).
- Netty TCP server at phase 0 SmartLifecycle with Epoll/NIO detection, AtomicInteger connection counting gauge, configurable max-connections limit, Caffeine cache manager with 3 pre-registered caches and explicit CaffeineCacheMetrics.monitor() per D-16, and Prometheus endpoint verified via GracefulShutdownTest.
- Multi-stage Dockerfile packaging tbmq-lightweight into eclipse-temurin:17-jre-jammy with HEALTHCHECK, VOLUME for RocksDB persistence, and human-verified end-to-end container operation (TCP on 1883, health UP, Prometheus metrics, graceful shutdown)
- Actor framework ported (19 files) with ForkJoinPool dispatcher at SmartLifecycle phase -1, plus session/protocol types (ClientSessionCtx, DisconnectReasonType, PacketIdAllocator) providing the stable contract for Plans 03-05
- Eclipse Paho MQTTv3 dependency + AbstractMqttIntegrationTest base class + 30 @Disabled stubs covering all PROTO-01 through PROTO-11 and TRAN-01 requirements
- Netty MQTT pipeline (MqttDecoder + MqttEncoder + MqttSessionHandler) wired through actor system enabling full CONNECT/CONNACK/PING/DISCONNECT flow with 1.5x keep-alive enforcement
- In-memory exact-match subscription registry with inline QoS 0/1/2 delivery, full PUBACK/PUBREC/PUBREL/PUBCOMP handshakes, and QoS 2 deduplication via inboundQos2 map
- ConcurrentHashMap-backed retained messages and LWT service with full client takeover support, completing MQTT 3.1.1 spec compliance for Phase 2
- ConcurrentMapSubscriptionTrie and ConcurrentMapRetainMsgTrie copied from TBMQ with StatsManager/Guava removed, providing O(topic-depth) wildcard matching with $SYS/ exclusion and 24 passing unit tests
- Queue-backed dispatch pipeline wired: publisher actors enqueue via MsgDispatcherService, consumer threads match wildcards via trie, deliver via actor mailbox — inline deliverToSubscribers() removed from ClientActor
- End-to-end dispatch pipeline validated: 89 tests green — wildcard delivery, $SYS/ exclusion, retained message wildcard, queue drop counter, and bounded queue factory all confirmed via integration and unit tests
- RocksDB-backed credential/ACL services with BCrypt password hashing, regex topic authorization, and default tbmq/tbmq credential installation via ApplicationReadyEvent.
- Auth and ACL wired into MQTT actor pipeline: processConnect() rejects invalid/anonymous with NOT_AUTHORIZED, processPublish() drops with counter, processSubscribe() returns 0x80, all proven by 9 new integration tests.
- Conditional TLS listener on port 8883 with BouncyCastle PEM loading, mTLS X.509 client cert authentication, and test PKI infrastructure — completes Phase 4 security stack
- AbstractServerBootstrap base class and MqttSessionHandlerFactory eliminate bootstrap/initializer duplication, with 4 WS frame handlers and WS/WSS config beans ready for the WS/WSS bootstraps in plan 05-02
- MQTT WebSocket (ws://:8084) and Secure WebSocket (wss://:8085) transports with Netty pipeline, subprotocol negotiation, and end-to-end integration tests.
- MQTT 5.0 type system and utility layer established — property extraction utilities, version-aware reason codes, topic alias context, extended domain models, and MqttMessageGenerator MQTT 5.0 overloads — all compilable and ready for wiring in Plans 02 and 03.
- Full MQTT 5.0 wire protocol wired through handler-actor-dispatcher: version negotiation, topic alias resolution, shared subscriptions with round-robin, subscription identifiers, reason codes in all ACKs, and receive maximum flow control
- 28 passing integration tests validating MQTT 5.0 version negotiation, properties forwarding, topic aliases, reason codes, and shared subscriptions with Paho v5 clients
- Five Prometheus metrics fully wired (received/delivered counters, auth success/failure counters, dispatch queue depth gauge) plus startup warning banner for TLS/volume/retained-msg misconfigurations.
- 1. [Rule 1 - Bug] surefire.excludedGroups static value prevented soak test from running

---
