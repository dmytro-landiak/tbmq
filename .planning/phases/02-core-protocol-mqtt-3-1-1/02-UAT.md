---
status: complete
phase: 02-core-protocol-mqtt-3-1-1
source: [02-01-SUMMARY.md, 02-02-SUMMARY.md, 02-03-SUMMARY.md, 02-04-SUMMARY.md, 02-05-SUMMARY.md]
started: 2026-04-07T18:50:00Z
updated: 2026-04-08T08:05:00Z
---

## Current Test

[testing complete]

## Tests

### 1. MQTT Client Connect and Disconnect
expected: Start the broker. Connect any MQTT 3.1.1 client to `tcp://localhost:1883`. The client receives CONNACK with return code 0 (accepted). Disconnect cleanly — no errors on either side.
result: pass

### 2. Publish and Subscribe QoS 0
expected: Open two MQTT clients. Client A subscribes to `test/topic`. Client B publishes a QoS 0 message to `test/topic`. Client A receives the message immediately with the correct payload.
result: pass

### 3. Publish and Subscribe QoS 1
expected: Client A subscribes to `test/qos1` with QoS 1. Client B publishes a QoS 1 message. Client A receives the message. The broker sends PUBACK to Client B after delivery.
result: pass

### 4. Publish and Subscribe QoS 2
expected: Client A subscribes to `test/qos2` with QoS 2. Client B publishes a QoS 2 message. The full handshake completes (PUBREC/PUBREL/PUBCOMP). Client A receives the message exactly once.
result: pass

### 5. Unsubscribe Stops Delivery
expected: Client A subscribes to `test/unsub`, receives a published message. Then Client A unsubscribes from `test/unsub`. A new message published to that topic is NOT delivered to Client A.
result: pass

### 6. Keep-Alive Timeout Disconnects Idle Client
expected: Connect a client with a short keep-alive (e.g., 5 seconds). Stop the client from sending any packets (including PINGREQ). After ~7.5 seconds (1.5x keep-alive), the broker closes the connection.
result: skipped
reason: Standard MQTT clients auto-send PINGREQ; cannot suppress without raw socket. Covered by MqttKeepAliveIntegrationTest which uses raw sockets.

### 7. Retained Message Delivered on Subscribe
expected: Client B publishes a message to `test/retained` with the retain flag set. Client B disconnects. Client A subscribes to `test/retained`. Client A immediately receives the retained message with the retain flag set, even though the publisher is gone.
result: pass

### 8. Clear Retained Message with Empty Payload
expected: After test 7, publish an empty payload to `test/retained` with retain=true. A new subscriber to `test/retained` does NOT receive any retained message.
result: pass

### 9. Last Will and Testament on Ungraceful Disconnect
expected: Client A subscribes to `lwt/topic`. Client B connects with a will message (topic=`lwt/topic`, payload="B is gone"). Kill Client B's connection ungracefully (e.g., close the socket without sending DISCONNECT). Client A receives the LWT message "B is gone".
result: pass
note: Verified via MqttLwtIntegrationTest (3/3 passed, raw socket used for ungraceful disconnect)

### 10. LWT Suppressed on Clean Disconnect
expected: Same setup as test 9 — Client B has a will message. But this time Client B sends a proper DISCONNECT packet. Client A does NOT receive the LWT message.
result: pass
note: Verified via MqttLwtIntegrationTest.testLwt_cleanDisconnect_willMessageNotDelivered

### 11. Client Takeover
expected: Client A connects with clientId "shared-id". Client B connects with the same clientId "shared-id". Client A's connection is closed by the broker. Client A's LWT is NOT published (suppressed during takeover). Client B is fully functional — can publish and subscribe.
result: pass

## Summary

total: 11
passed: 10
issues: 0
pending: 0
skipped: 1
blocked: 0

## Gaps

[none]
