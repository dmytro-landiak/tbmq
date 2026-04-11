---
phase: "07"
slug: hardening-and-docker-release
status: verified
threats_open: 0
asvs_level: 1
created: 2026-04-11
---

# Phase 07 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Metrics endpoint | /actuator/prometheus exposed on management port | Aggregate counter values (message rates, auth attempts) — no PII |
| /proc/mounts | OS filesystem metadata read inside container | Mount point paths and filesystem types — no secrets |
| Soak test JVM | Test runs in same JVM as broker with access to all Spring beans | MeterRegistry counters and gauges — test-only |
| ARM64 script | Runs on user's machine with Docker access | Creates/destroys containers, publishes MQTT test messages |

---

## Threat Register

| Threat ID | Category | Component | Disposition | Mitigation | Status |
|-----------|----------|-----------|-------------|------------|--------|
| T-07-01 | Information Disclosure | /actuator/prometheus endpoint | accept | Metrics expose aggregate counts only (message rates, auth attempts). No PII, usernames, or topic names. Separate management port (configurable, default 8083). Standard observability practice. | closed |
| T-07-02 | Information Disclosure | Auth counter metric tags | mitigate | Auth counters (`mqtt.auth.success.total`, `mqtt.auth.failure.total`) use zero-tag aggregate counters with no username/clientId labels. Verified: `Counter.builder()` at `DefaultLightweightAuthService.java:70-75` has no `.tag()` calls. Prevents high-cardinality label leakage via Prometheus scrape. | closed |
| T-07-03 | Information Disclosure | Startup warning log output | accept | Startup warnings log boolean configuration states (TLS enabled/disabled) and filesystem paths only. No secrets logged. Output visible only in container logs (`docker logs`). Standard operational logging. | closed |
| T-07-04 | Denial of Service | /proc/mounts file reading | accept | One-time read at startup (`@EventListener(ApplicationReadyEvent.class)`). Returns `false` on `IOException` — no retry loop, no blocking I/O on hot path. | closed |
| T-07-05 | Denial of Service | Soak test resource consumption | accept | Intentional load for validation (500 clients, 1000 msg/sec). Excluded from default `mvn test` via `<excludedGroups>soak</excludedGroups>`. Only runs when explicitly invoked via `-Dgroups=soak`. | closed |
| T-07-06 | Tampering | ARM64 validation script | accept | Script is read-only from broker perspective — only connects as an MQTT client. Uses cleanup trap (`trap cleanup EXIT`) to remove containers and temp directories on exit. No risk of data corruption. | closed |

*Status: open · closed*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-07-01 | T-07-01 | Prometheus metrics are industry-standard observability. Aggregate counts contain no PII. Management port can be firewalled in production. | phase plan | 2026-04-11 |
| AR-07-03 | T-07-03 | Startup warnings log configuration state to help operators. No secrets in output. Container logs access is controlled by infrastructure. | phase plan | 2026-04-11 |
| AR-07-04 | T-07-04 | /proc/mounts read is non-blocking, one-time, and fails gracefully. No amplification vector. | phase plan | 2026-04-11 |
| AR-07-05 | T-07-05 | Soak test resource consumption is intentional and gated behind explicit test group activation. | phase plan | 2026-04-11 |
| AR-07-06 | T-07-06 | ARM64 script operates as a standard MQTT client with cleanup on exit. No elevated privileges required. | phase plan | 2026-04-11 |

*Accepted risks do not resurface in future audit runs.*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-04-11 | 6 | 6 | 0 | gsd-secure-phase |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-04-11
