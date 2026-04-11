---
status: complete
phase: 06-mqtt-5-0
source: [06-01-SUMMARY.md, 06-02-SUMMARY.md, 06-03-SUMMARY.md]
started: 2026-04-11T17:30:00.000Z
updated: 2026-04-11T17:45:00.000Z
---

## Current Test

[testing complete]

## Tests

### 1. Cold Start Smoke Test
expected: Kill any running broker. Clear /tmp/tbmq-rocksdb if it exists. Start with `TBMQ_ROCKSDB_PATH=/tmp/tbmq-rocksdb mvn spring-boot:run -f lightweight/pom.xml`. Broker boots without errors, logs show "Started TBMQ Lightweight", MQTT TCP listener starts on port 1883.
result: pass

### 2. MQTT 5.0 client connects successfully
expected: Connect an MQTT 5.0 client (mosquitto_sub -V 5 -u tbmq -P tbmq -h 127.0.0.1 -t test/#). Connection succeeds without errors. Broker log shows no exceptions.
result: pass

### 3. MQTT 5.0 and 3.1.1 coexistence
expected: Connect one MQTT 5.0 client and one MQTT 3.1.1 client simultaneously. Both connect successfully on port 1883. Publish from 3.1.1, receive on 5.0 (and vice versa). Messages delivered correctly between protocol versions.
result: pass

### 4. User properties forwarded end-to-end
expected: V5 subscriber subscribes to `props/test`. V5 publisher publishes with a user property. Subscriber receives the message with user property intact.
result: pass

### 5. Shared subscription delivers to one group member
expected: Start 2+ subscribers on `$share/grp1/shared/topic`. Publish 10+ messages to `shared/topic`. Each message delivered to exactly one subscriber. Total received across all subscribers equals total published.
result: pass

### 6. Shared subscription round-robin fairness
expected: Start 3 subscribers on `$share/grp1/load/test`. Publish 100+ messages to `load/test`. Each subscriber receives roughly 1/3 of messages (within 10% variance). No subscriber gets 0 messages.
result: pass

### 7. Topic alias does not break message delivery
expected: V5 publisher publishes 5+ messages to the same long topic name. V5 subscriber receives all messages with correct topic name.
result: pass

### 8. Retained message expiry works
expected: Publish a retained message with message expiry interval = 2 seconds. Wait 3+ seconds. Subscribe to the topic. No retained message is delivered (it expired). Publish another retained message with long expiry, subscribe immediately — it IS delivered.
result: pass

### 9. Auth failure returns correct reason code
expected: Connect an MQTT 5.0 client with wrong password. Connection is rejected. Client reports NOT_AUTHORIZED or BAD_USER_NAME_OR_PASSWORD error.
result: pass

## Summary

total: 9
passed: 9
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
