---
status: complete
phase: 05-websocket-transport
source: [05-01-SUMMARY.md, 05-02-SUMMARY.md]
started: 2026-04-11T10:00:00Z
updated: 2026-04-11T11:56:00Z
---

## Current Test

[testing complete]

## Tests

### 1. WebSocket Connect and Pub/Sub
expected: Run `MqttWsIntegrationTest`. All 3 tests pass: WS connect gets CONNACK, pub/sub delivers message over ws://, and WS+TCP coexist with cross-transport delivery.
result: pass

### 2. Secure WebSocket Connect and Pub/Sub
expected: Run `MqttWssIntegrationTest`. All 3 tests pass: WSS connect over TLS gets CONNACK, pub/sub delivers message over wss://, and WSS+WS coexist with cross-transport delivery.
result: pass

### 3. Existing TCP/TLS Tests Unbroken
expected: Run the full existing test suite (excluding WS/WSS tests). All pre-existing integration tests pass — the AbstractServerBootstrap refactoring is behavior-preserving. No regressions in TCP or TLS transport.
result: pass

### 4. WebSocket Subprotocol Header
expected: The `WebSocketServerProtocolHandler` is configured with subprotocols `"mqttv3.1,mqtt"`. When a client sends `Sec-WebSocket-Protocol: mqtt`, the broker echoes it back. Paho WS client connects without `HandshakeFailedException`. Verified implicitly by test 1 passing.
result: pass

### 5. WSS Safety Guard
expected: When TLS is not configured (`tbmq.tls.enabled=false`), WSS bootstrap does NOT start even if `tbmq.wss.enabled=true`. The `MqttWssServerBootstrap.isAutoStartup()` returns false when `tlsConfig.isEnabled()` is false, and `start()` logs a warning if misconfigured.
result: pass

### 6. WS/WSS Configuration Defaults
expected: In `tbmq-lightweight.yml`, WS is enabled by default on port 8084, WSS is disabled by default on port 8085. Config properties use `${ENV_VAR:default}` pattern for all WS/WSS settings (port, enabled, subprotocols, maxContentLength).
result: pass

## Summary

total: 6
passed: 6
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none]
