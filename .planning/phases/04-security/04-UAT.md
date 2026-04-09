---
status: complete
phase: 04-security
source: [04-01-SUMMARY.md, 04-02-SUMMARY.md, 04-03-SUMMARY.md]
started: 2026-04-09T11:00:00Z
updated: 2026-04-09T11:00:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Valid credentials connect successfully
expected: Client with tbmq/tbmq credentials connects and receives CONNACK ACCEPTED. Verified by `MqttAuthIntegrationTest#givenValidCredentials_whenConnect_thenAccepted`
result: pass

### 2. Invalid credentials rejected with NOT_AUTHORIZED
expected: Client with wrong password receives CONNACK code 5 (NOT_AUTHORIZED) and channel closes. Verified by `MqttAuthIntegrationTest#givenInvalidPassword_whenConnect_thenNotAuthorized`
result: pass

### 3. Anonymous access denied by default
expected: Client with no credentials rejected when TBMQ_SECURITY_ANONYMOUS_ENABLED=false (default). Verified by `MqttAuthIntegrationTest#givenNoCredentials_whenConnectAnonymously_thenDefaultDenied`
result: pass

### 4. Publish to unauthorized topic dropped
expected: Client publishes to topic not in ACL pubPatterns — message silently dropped, denial counter incremented. Verified by `MqttAclIntegrationTest`
result: pass

### 5. Subscribe to unauthorized topic returns SUBACK 0x80
expected: Client subscribes to topic not in ACL subPatterns — SUBACK returns 0x80 failure code per MQTT 3.1.1 spec. Verified by `MqttAclIntegrationTest`
result: pass

### 6. Default credentials installed on first start
expected: tbmq/tbmq credential auto-installed when CREDENTIALS CF is empty. Verified by `CredentialServiceTest` + all auth integration tests use these defaults
result: pass

### 7. Credentials persist in RocksDB
expected: Credentials stored in CREDENTIALS column family with JSON serialization, survives across test lifecycle. Verified by `CredentialServiceTest`
result: pass

### 8. TLS connection on port 8883
expected: MQTT client connects over TLS with trusted CA cert. Verified by `MqttTlsIntegrationTest#testTlsConnection_withTrustedCa_succeeds`
result: pass

### 9. TLS and plain TCP coexist
expected: Both port 1883 (plain) and 8883 (TLS) accept connections simultaneously. Verified by `MqttTlsIntegrationTest#testTlsAndPlainTcp_coexist`
result: pass

### 10. mTLS with valid client certificate
expected: Client presenting CA-signed X.509 cert (CN=test-client) authenticates without username/password. Verified by `MqttMtlsIntegrationTest#testMtlsConnection_withValidClientCert_succeeds`
result: pass

### 11. mTLS rejects missing client certificate
expected: Client connecting to mTLS port without presenting a cert is rejected at TLS layer. Verified by `MqttMtlsIntegrationTest#testMtlsConnection_withoutClientCert_rejected`
result: pass

### 12. Full test suite regression check
expected: `mvn test -q` — 113 tests pass, 0 failures, 3 pre-existing @Disabled skips. All Phase 2 and 3 tests still green.
result: pass

## Summary

total: 12
passed: 12
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
