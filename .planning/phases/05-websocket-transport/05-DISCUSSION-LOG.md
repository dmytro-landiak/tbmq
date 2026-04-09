# Phase 5: WebSocket Transport - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-09
**Phase:** 05-websocket-transport
**Areas discussed:** WSS certificate config, Bootstrap refactoring, Subprotocol values, Channel initializer sharing

---

## WSS Certificate Config

| Option | Description | Selected |
|--------|-------------|----------|
| Shared certs (Recommended) | WSS and MQTTS use the same PEM cert/key/truststore from Phase 4's TBMQ_TLS_* env vars. Simpler Docker setup — one volume mount covers both. | ✓ |
| Separate cert config | WSS gets its own TBMQ_WSS_CERT_PATH, TBMQ_WSS_KEY_PATH etc. Matches TBMQ's pattern (separate listener.wss.config.credentials). More flexible but more env vars. | |
| You decide | Claude picks the approach that best fits lightweight's single-node simplicity | |

**User's choice:** Shared certs (Recommended)
**Notes:** None — straightforward choice aligned with lightweight's simplicity goal.

---

## Bootstrap Refactoring

| Option | Description | Selected |
|--------|-------------|----------|
| Extract abstract base (Recommended) | Create AbstractServerBootstrap with shared Epoll/NIO detection, boss/worker group lifecycle, bind/shutdown logic. Each concrete bootstrap only provides config. Eliminates ~80 lines of duplication per bootstrap. | ✓ |
| Keep flat bootstraps | Keep each bootstrap self-contained with duplicated Netty setup. Simpler to read individually, but 4x duplication. | |
| You decide | Claude picks based on code volume and maintenance trade-offs | |

**User's choice:** Extract abstract base (Recommended)
**Notes:** Also retroactively cleans up TCP and TLS bootstraps from earlier phases.

---

## Subprotocol Values

| Option | Description | Selected |
|--------|-------------|----------|
| Both: mqttv3.1,mqtt (Recommended) | Match TBMQ's default. 'mqtt' represents both MQTT 3.1.1 and 5.0 per OASIS standard. Browser clients (MQTT.js) typically request 'mqtt'. Avoids breaking changes when Phase 6 adds MQTT 5.0. | ✓ |
| Only mqttv3.1 | Strictly reflect current capability. Add 'mqtt' in Phase 6. Risk: MQTT.js and other browser clients default to requesting 'mqtt' and may fail handshake. | |
| You decide | Claude picks based on client compatibility research | |

**User's choice:** Both: mqttv3.1,mqtt (Recommended)
**Notes:** Forward compatibility with browser clients is the deciding factor.

---

## Channel Initializer Sharing

| Option | Description | Selected |
|--------|-------------|----------|
| Handler factory (Recommended) | Create MqttSessionHandlerFactory encapsulating all 12 dependencies, producing new MqttSessionHandler instances. Each initializer injects only the factory + transport extras. Matches TBMQ's MqttHandlerFactory pattern. | ✓ |
| Abstract base initializer | Extract abstract ChannelInitializer with shared pipeline. Subclasses prepend SSL or WS handlers. Requires careful override design. | |
| Keep duplication | Each initializer duplicates all dependencies. Currently works for 2, but 4 makes it painful. | |
| You decide | Claude picks the cleanest approach based on TBMQ patterns | |

**User's choice:** Handler factory (Recommended)
**Notes:** Eliminates 12-dependency duplication and follows TBMQ's established pattern.

---

## Claude's Discretion

- Exact abstract bootstrap class name and method decomposition
- Whether to use @ConditionalOnProperty or isAutoStartup() for WS/WSS enable/disable
- Handler factory method signatures and constructor design
- Integration test approach for WebSocket transport
- Whether WS/WSS listeners share the TCP bootstrap's thread pool or get their own

## Deferred Ideas

None — discussion stayed within phase scope.
