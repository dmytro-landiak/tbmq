# Phase 2: Core Protocol (MQTT 3.1.1) - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md -- this log preserves the alternatives considered.

**Date:** 2026-04-06
**Phase:** 02-core-protocol-mqtt-3-1-1
**Areas discussed:** Session architecture, Message routing boundary, Subscription matching scope, MQTT spec compliance depth

---

## Session Architecture

### Q1: Per-client session concurrency model

| Option | Description | Selected |
|--------|-------------|----------|
| Actor-per-client | Port TBMQ's actor model (simplified). Each client ID gets a lightweight actor with a mailbox queue. Guarantees sequential processing per client. | **selected** |
| Session registry + striped locks | ConcurrentHashMap with per-session ReentrantLock. Simpler but manual lock management. | |
| Channel-scoped handler | Non-shared handler per channel, Netty guarantees single-threaded execution. Simplest but client takeover needs cross-channel coordination. | |

**User's choice:** Actor-per-client
**Notes:** Aligns with TBMQ's proven pattern. Natural isolation for client takeover, QoS state tracking, and LWT management.

### Q2: Actor framework implementation

| Option | Description | Selected |
|--------|-------------|----------|
| Simplified custom actors | Lightweight actor system purpose-built for single-node. Cleaner API, fewer abstractions. | |
| Port TBMQ's common/actor | Copy TbActorSystem, TbActorMailbox, AbstractTbActor from common/actor/. Battle-tested but heavier abstractions. | **selected** |
| You decide | Claude picks based on Phase 2 needs. | |

**User's choice:** Port TBMQ's common/actor
**Notes:** Proven and battle-tested -- worth the extra abstraction weight for reliability.

---

## Message Routing Boundary

### Q1: Phase 2 message routing approach

| Option | Description | Selected |
|--------|-------------|----------|
| Direct inline delivery | Publisher's actor looks up subscriptions, writes directly to subscriber channels. Phase 3 refactors to dispatch service. | **selected** |
| Minimal dispatch service from day one | Introduce PublishMsgQueueFactory + LinkedBlockingQueue immediately. Less Phase 3 refactoring. | |
| You decide | Claude picks based on phase boundary analysis. | |

**User's choice:** Direct inline delivery
**Notes:** Simple, correct, fast for Phase 2. Keeps Phase 3's dispatch abstraction clean as a refactoring step.

### Q2: Subscription registry data structure

| Option | Description | Selected |
|--------|-------------|----------|
| ConcurrentHashMap | Simple exact-match lookup. Phase 3 replaces with concurrent trie. | **selected** |
| Basic trie structure | Trie skeleton now, exact matching only. Phase 3 extends with wildcard traversal. | |

**User's choice:** ConcurrentHashMap
**Notes:** Minimal code, easy to test. Phase 3 replaces entirely with concurrent trie.

---

## Subscription Matching Scope

### Q1: Wildcard subscription support in Phase 2

| Option | Description | Selected |
|--------|-------------|----------|
| Exact matching only | Wildcard subscriptions accepted/stored but only exact topics deliver. Phase 3 enables wildcard delivery. | **selected** |
| Basic wildcard support now | Simple brute-force wildcard matching. More usable for testing but Phase 3 replaces anyway. | |
| Reject wildcards in Phase 2 | Return SUBACK failure for wildcards. Strict scope but may surprise clients. | |

**User's choice:** Exact matching only
**Notes:** Wildcards accepted and stored (valid SUBACK), but delivery only on exact match. Phase 3 lights up wildcard delivery.

---

## MQTT Spec Compliance Depth

### Q1: MQTT 3.1.1 compliance level

| Option | Description | Selected |
|--------|-------------|----------|
| Spec-complete | All MUST and SHOULD requirements covered. No compliance debt deferred. | **selected** |
| Happy path + critical MUSTs | Core flows correct, defer SHOULD-level edge cases to Phase 7 hardening. | |
| You decide | Claude determines compliance level based on success criteria. | |

**User's choice:** Spec-complete
**Notes:** No compliance debt carried to later phases. All MUST/SHOULD from MQTT 3.1.1 spec implemented in Phase 2.

---

## Claude's Discretion

- Exact class names and package layout for protocol handlers
- QoS 2 state machine implementation details
- Packet ID allocator implementation
- LWT storage and delivery mechanism internals
- Keep-alive timer approach
- Client takeover coordination internals
- MQTT message builder/generator utility structure

## Deferred Ideas

None -- discussion stayed within phase scope
