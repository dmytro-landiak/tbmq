---
phase: 05
slug: websocket-transport
status: verified
threats_open: 0
asvs_level: 1
created: 2026-04-11
---

# Phase 05 Security Audit — WebSocket Transport

## Trust Boundaries

| ID | Boundary | Direction | Notes |
|----|----------|-----------|-------|
| TB-1 | Network → Netty WS pipeline | Inbound | Untrusted HTTP upgrade request flows through HttpServerCodec → HttpObjectAggregator → WebSocketServerProtocolHandler → WS frame handlers → MQTT codec → MqttSessionHandler |
| TB-2 | Network → Netty WSS pipeline | Inbound | Untrusted bytes enter TLS termination (SslHandler, first in pipeline) before the same WS upgrade path as TB-1 |
| TB-3 | WS frame handlers → MQTT codec | Internal | Frame protocol boundary: WsBinaryFrameHandler/WsContinuationFrameHandler unwrap WS framing to raw ByteBuf; WsTextFrameHandler rejects non-binary frames |

## Threat Register

| Threat ID | Category | STRIDE | Disposition | Status | Evidence |
|-----------|----------|--------|-------------|--------|----------|
| T-05-01 | WS auth bypass | Spoofing | mitigate | CLOSED | `MqttWsChannelInitializer.java:96` and `MqttWssChannelInitializer.java:102` — `sessionHandlerFactory.create()` injects full `MqttSessionHandler` with `LightweightAuthService` and `AuthorizationRuleService` |
| T-05-02 | HTTP upgrade manipulation | Tampering | mitigate | CLOSED | `MqttWsChannelInitializer.java:84` and `MqttWssChannelInitializer.java:90` — `WebSocketServerProtocolHandler(WS_PATH, subProtocols)` manages full HTTP upgrade state machine; invalid or malformed upgrades are rejected by Netty |
| T-05-03 | Large HTTP content in WS handshake | DoS | mitigate | CLOSED | `MqttWsChannelInitializer.java:83` — `new HttpObjectAggregator(wsConfig.getMaxContentLength())`; `MqttWssChannelInitializer.java:89` — `new HttpObjectAggregator(wssConfig.getMaxContentLength())`. Default 65536 bytes enforced in `WsConfiguration.java:47` and `WssConfiguration.java:47`; also declared in `tbmq-lightweight.yml:38,43` |
| T-05-04 | WS text frame abuse | DoS | mitigate | CLOSED | `WsTextFrameHandler.java:32` — `ctx.channel().disconnect()` called immediately on receipt of any `TextWebSocketFrame`; debug log records remote address |
| T-05-05 | WS exposes non-/mqtt HTTP paths | Information Disclosure | mitigate | CLOSED | `MqttWsChannelInitializer.java:67` — `private static final String WS_PATH = "/mqtt"` passed as first arg to `WebSocketServerProtocolHandler`; equivalent constant in `MqttWssChannelInitializer.java:71`. Non-matching paths are not upgraded and receive HTTP 400 from Netty |
| T-05-06 | WSS starts without TLS | Elevation of Privilege | mitigate | CLOSED | `MqttWssServerBootstrap.java:70` — `isAutoStartup()` returns `wssConfig.isEnabled() && tlsConfig.isEnabled()`. `start()` method has explicit dual guard at lines 75-83: returns with `log.info` if WSS disabled, returns with `log.warn` if WSS enabled but TLS not configured |
| T-05-07 | ByteBuf reference leak | Tampering | mitigate | CLOSED | `WsBinaryFrameHandler.java:26` — `msg.content().retain()`; `WsContinuationFrameHandler.java:26` — `msg.content().retain()`; `WsByteBufEncoder.java:31` — `ReferenceCountUtil.retain(binaryWebSocketFrame)`. Both inbound handlers extend `SimpleChannelInboundHandler` which auto-releases the original WS frame after `channelRead0`, making the explicit `retain()` on the extracted content mandatory to prevent premature deallocation |
| T-05-08 | Cross-origin WebSocket hijacking (CSWSH) | Spoofing | accept | CLOSED | See Accepted Risks Log below |

## Unregistered Flags

None. Neither `05-01-SUMMARY.md` nor `05-02-SUMMARY.md` contains a `## Threat Flags` section. No new attack surface was flagged by the executor during implementation.

## Accepted Risks Log

| Risk ID | Threat | Rationale | Owner | Review Date |
|---------|--------|-----------|-------|-------------|
| AR-05-01 | T-05-08 — Cross-origin WebSocket hijacking (CSWSH) | `WebSocketServerProtocolHandler` does not validate the `Origin` header on WS upgrade. For a browser-based CSWSH attack to succeed against this broker, an attacker must also present valid MQTT credentials (username/password, client certificate, or SCRAM token) after the WebSocket handshake completes. The MQTT authentication layer provides equivalent protection for an MQTT broker: unlike a session-cookie-authenticated web application, MQTT auth is credential-based and not ambient. Accepting this risk is consistent with the upstream TBMQ implementation and industry practice for MQTT-over-WebSocket brokers. If the broker is ever deployed in an environment where browser access is expected from untrusted origins, an Origin-checking `ChannelHandler` should be added before `WebSocketServerProtocolHandler`. | Phase 05 executor | 2027-04-11 |

## Security Audit Trail

| Date | Auditor | Action |
|------|---------|--------|
| 2026-04-11 | gsd-security-auditor (claude-sonnet-4-6) | Initial audit of Phase 05 WebSocket Transport. Verified 8 derived threats against implementation. 7 mitigations confirmed in code. 1 accepted risk documented. |

## Sign-Off Checklist

- [x] All `<files_to_read>` loaded before analysis
- [x] Threat register extracted and all 8 threats classified by disposition
- [x] All `mitigate` threats (T-05-01 through T-05-07) verified against implementation with file:line evidence
- [x] All `accept` threats (T-05-08) logged in Accepted Risks Log
- [x] No `transfer` threats in this phase
- [x] Threat flags from SUMMARY.md checked — none present
- [x] Implementation files not modified
- [x] `threats_open: 0`
- [x] ASVS Level 1 coverage confirmed
