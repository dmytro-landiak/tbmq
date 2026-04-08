# Phase 3: Message Dispatch - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-08
**Phase:** 03-message-dispatch
**Areas discussed:** Backpressure strategy, Retained message wildcard delivery, Dispatch threading model

---

## Backpressure Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Drop oldest messages | Queue evicts oldest undelivered messages to make room. Publisher never blocks. | |
| Block publisher actor | Publisher's actor thread waits until queue has space (bounded put()). Guarantees order but risks actor stall. | |
| Reject with configurable queue capacity | Hard cap on queue. When full, new messages dropped with metric increment. Publisher gets normal QoS acks. | ✓ |
| You decide | Claude picks based on TBMQ patterns. | |

**User's choice:** Reject with configurable queue capacity
**Notes:** Publisher still receives PUBACK/PUBREC normally — QoS ack is about broker receipt, not delivery. Dropped messages tracked via Micrometer counter.

---

## Retained Message Wildcard Delivery

| Option | Description | Selected |
|--------|-------------|----------|
| Separate retained message trie | Own trie structure for retained messages. On subscribe, traverse trie with topic filter. O(topic-depth) lookup. | ✓ |
| Iterate all retained messages | Scan every retained message topic against subscriber's filter. O(n) where n = total retained messages. | |
| Reuse subscription trie in reverse | Insert retained topics into subscription trie temporarily. Hacky, not recommended. | |
| You decide | Claude picks based on TBMQ patterns. | |

**User's choice:** Separate retained message trie
**Notes:** Clean and efficient. Same trie data structure as subscriptions but stores RetainedMessage values.

---

## Dispatch Threading Model

| Option | Description | Selected |
|--------|-------------|----------|
| Fixed thread pool, configurable | e.g., TBMQ_DISPATCH_THREADS=4. Predictable resource usage. | |
| Single consumer thread | One thread drains queue sequentially. Simplest. | |
| ForkJoinPool (shared with actors) | Reuse existing actor executor. No extra threads. | |
| You decide | Claude picks based on load profile and TBMQ patterns. | ✓ |

**User's choice:** You decide (Claude's discretion)
**Notes:** None — deferred to planner/researcher.

---

## Claude's Discretion

- Dispatch consumer threading model (type, sizing, configuration)

## Deferred Ideas

None — discussion stayed within phase scope
