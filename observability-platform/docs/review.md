# Code Review — Observability Platform

**Date:** 2026-03-23
**Scope:** Full project — all 3 services, Docker/infra configs, tests, dashboards, scripts, POM files, README

---

## Executive Summary

| Severity | Count |
|----------|-------|
| Critical | 5 |
| High | 16 |
| Medium | 26 |
| Low | 18 |

**Top 3 systemic issues:**
1. **Unreliable event pipeline** — Kafka producer swallows failures, publishes before transaction commits, consumer silently drops errors with no retry/DLT. Events can be lost without any indication.
2. **Always-on chaos engineering** — Risk service's random failures/delays have no config flag, causing flaky tests and production unreliability.
3. **Security gaps** — No authentication, hardcoded prod passwords, Grafana anonymous admin access, no input validation on risk service.

---

## Critical Issues

### C1. Kafka producer failure silently swallowed — data loss
**File:** `order-service/.../OrderEventPublisher.java:40-42`

The `catch (Exception e)` logs but never rethrows. The order is committed to DB, but the Kafka event is lost. The notification-service never learns about the order. The `kafkaTemplate.send()` returns a `CompletableFuture` that is never inspected (fire-and-forget), so even async failures are invisible.

**Fix:** Either (a) let exceptions propagate to roll back the transaction, (b) implement the Transactional Outbox pattern, or (c) at minimum call `.get()` on the send future and rethrow failures.

### C2. Kafka event published before transaction commits
**File:** `order-service/.../OrderService.java:49-52`

`eventPublisher.publishOrderCreated(saved)` is called inside `@Transactional`. If the commit fails after the Kafka send, the notification-service receives an event for a non-existent order (phantom event).

**Fix:** Use `@TransactionalEventListener(phase = AFTER_COMMIT)` or the Transactional Outbox pattern.

### C3. Kafka consumer silently drops all errors — no retry/DLT
**File:** `notification-service/.../OrderEventConsumer.java:55-57`

The `catch (Exception e)` logs and swallows every exception — poison messages, transient DB failures, and NPEs all vanish. Kafka offset is committed, so failed messages are never reprocessed. No Dead Letter Topic is configured.

**Fix:** Remove the broad catch, configure a `DefaultErrorHandler` with retry backoff and `DeadLetterPublishingRecoverer`. Distinguish retryable vs non-retryable exceptions.

### C4. Risk service chaos always active — flaky tests, prod failures
**File:** `risk-service/.../RiskController.java:41-49`

20% chance of 500ms `Thread.sleep()` and 5% chance of `RuntimeException` with no config flag to disable. Tests are non-deterministic (~5% random failure rate per test). In production, 5% of legitimate requests fail randomly.

**Fix:** Gate behind `@Value("${chaos.enabled:false}")` or `@Profile("chaos")`. Disable in tests.

### C5. Tempo storage path mismatch — trace data lost on restart
**File:** `docker/tempo-config.yaml:17,19` vs `docker/docker-compose.yml:72`

Volume mounts `tempo-data:/tmp/tempo` but Tempo config writes to `/var/tempo/traces` and `/var/tempo/wal`. Trace data is written to an unmounted path and lost on container restart.

**Fix:** Change docker-compose volume to `tempo-data:/var/tempo` or change config paths to `/tmp/tempo/traces` and `/tmp/tempo/wal`.

---

## High Severity Issues

### H1. No authentication/authorization on any endpoint
**Files:** All 3 services — no `spring-boot-starter-security` dependency

All REST endpoints are completely open. Any caller can create orders for any userId, enumerate all orders, and access actuator endpoints.

**Fix:** Add `spring-boot-starter-security` with JWT/OAuth2 authentication. Extract userId from security context, not request body.

### H2. Hardcoded database passwords in production compose
**Files:** `docker/docker-compose.prod.yml:5,19,113-115`

Trivial passwords (`orders`/`notifications`) in source-controlled production compose file.

**Fix:** Use Docker secrets or environment variable references without defaults. Add `.env` file to `.gitignore`.

### H3. No input validation on risk service
**File:** `risk-service/.../RiskController.java:39`

`RiskEvaluationRequest` has no `@Valid` annotation. Null `amount` causes `NullPointerException` → raw 500. No `spring-boot-starter-validation` dependency in risk-service POM.

**Fix:** Add `spring-boot-starter-validation`, add `@Valid` + `@NotNull @Positive` on amount, add a `@RestControllerAdvice` exception handler.

### H4. No global exception handler in risk-service
**File:** `risk-service/` — missing `@RestControllerAdvice`

Unlike order-service, risk-service has no exception handler. Unhandled exceptions (the deliberate RuntimeException, NPEs, InterruptedException) produce raw stack traces.

**Fix:** Add a `@RestControllerAdvice` with catch-all handler.

### H5. Idempotency check has TOCTOU race condition
**File:** `notification-service/.../OrderEventConsumer.java:38-52`

Check-then-insert pattern: `existsByEventId` → (gap) → `save`. Two consumers processing the same message could both pass the check. The unique constraint catches it, but the resulting `DataIntegrityViolationException` is swallowed by the catch block (C3).

**Fix:** Catch `DataIntegrityViolationException` specifically as a handled duplicate. Add `@Transactional`. Or use upsert pattern.

### H6. No null checks on JSON field access in consumer
**File:** `notification-service/.../OrderEventConsumer.java:35,43-49`

`root.get("id").asText()` etc. — if any field is missing, `get()` returns null → NPE, which is then silently swallowed. Malformed events are invisible.

**Fix:** Use Jackson `path()` instead of `get()`, or deserialize into a typed record/POJO with validation.

### H7. Thread.sleep blocks servlet thread
**File:** `risk-service/.../RiskController.java:43`

500ms `Thread.sleep()` on 20% of requests blocks Tomcat threads. Under load, this can exhaust the thread pool and cause cascading failures.

**Fix:** Make chaos configurable (see C4). If retained, consider virtual threads (Java 21).

### H8. Missing database indexes on orders table
**File:** `order-service/.../V1__create_orders_table.sql`

No indexes beyond PK. Queries by `user_id`, `status`, or `created_at` will full-table-scan.

**Fix:** Add migration: `CREATE INDEX idx_orders_user_id ON orders (user_id); CREATE INDEX idx_orders_status ON orders (status);`

### H9. Kafka producer acks=1 default — message loss risk
**File:** `order-service/.../application.yml:19-21`

Default `acks=1` means only the leader acknowledges. Leader failure before replication = message lost. Unacceptable for financial events.

**Fix:** Set `spring.kafka.producer.acks: all`.

### H10. `:latest` tags in production compose
**File:** `docker/docker-compose.prod.yml:109,137,152`

Non-reproducible deployments, no rollback capability.

**Fix:** Tag with git SHA or semantic version: `${ECR_URI}/observability-platform/order-service:${VERSION}`.

### H11. No restart policies in either compose file
**Files:** `docker/docker-compose.yml`, `docker/docker-compose.prod.yml`

Crashed containers stay down.

**Fix:** Add `restart: unless-stopped` (dev) / `restart: always` (prod).

### H12. No resource limits in either compose file
**Files:** Both compose files

On a t3.medium (4GB RAM) with 12 containers, any runaway JVM/Kafka OOM takes down the entire host.

**Fix:** Add `mem_limit` per service. Budget: 3 JVMs at 512MB, Kafka 768MB, 2 Postgres at 384MB, etc.

### H13. OTel Collector missing batch processor
**File:** `docker/otel-collector-config.yaml:13-17`

No `batch` processor — every span is exported individually, causing excessive network overhead.

**Fix:** Add `processors: { batch: {} }` and reference in pipeline.

### H14. All internal ports published to host (dev)
**File:** `docker/docker-compose.yml`

Postgres, Kafka, Prometheus, OTel Collector, Tempo, Loki all exposed. Dangerous if host is network-accessible.

**Fix:** Bind to loopback: `127.0.0.1:9090:9090` or remove unnecessary port mappings.

### H15. ALB on HTTP with default Grafana credentials
**Files:** `infra/aws/aws-setup.sh:69`, `docker/docker-compose.prod.yml:92`

Public ALB on port 80 (no TLS) fronting Grafana with `admin/admin` password and anonymous access enabled.

**Fix:** Add HTTPS listener with ACM cert. Disable anonymous access in prod. Set strong admin password.

### H16. `StrictHostKeyChecking=no` in deploy script
**File:** `scripts/deploy.sh:45`

Vulnerable to MITM attacks on first SSH connection.

**Fix:** Use `StrictHostKeyChecking=accept-new`.

---

## Medium Severity Issues

| # | Service/File | Description |
|---|-------------|-------------|
| M1 | `order-service/OrderService.java:59` | `getOrder` returns null instead of throwing; callers must null-check |
| M2 | `order-service/OrderEventPublisher.java:34` | NPE risk: `order.getRiskScore()` can be null; `Map.of()` rejects null |
| M3 | `order-service/OrderService.java:37-55` | `@Transactional` held open during Kafka send |
| M4 | `order-service/GlobalExceptionHandler.java` | No catch-all handler; malformed JSON, invalid UUID → raw stack trace |
| M5 | `order-service/OrderController.java:40-44` | `listOrders` has no max page size limit; `?size=999999` allowed |
| M6 | `order-service/application.yml` | No HikariCP connection pool tuning |
| M7 | `order-service/Order.java:33` | Status as raw String; should be enum for type safety |
| M8 | `order-service/` | No test for `OrderEventPublisher` (Kafka publishing untested) |
| M9 | `order-service/OrderIntegrationTest.java` | Integration test doesn't verify Kafka event was published |
| M10 | `order-service/OrderIntegrationTest.java` | Shared DB state; no cleanup between tests |
| M11 | `order-service/` | No tests for `listOrders` pagination or `RiskClient` circuit breaker |
| M12 | `order-service/application.yml:19-21` | No Kafka producer idempotence config; duplicates possible on retry |
| M13 | `order-service/OrderEventPublisher.java:38` | No `NewTopic` bean; topic may not exist if auto-create is disabled |
| M14 | `order-service/OrderService.java:29-34` | Counter name `orders.created.total` exports as `orders_total` (confusing) |
| M15 | `order-service/` | No metrics for rejected orders or Kafka publish failures |
| M16 | `risk-service/RiskController.java:39` | `throws InterruptedException` leaked through API |
| M17 | `risk-service/RiskController.java:69-72` | Counter created per-request instead of pre-registered |
| M18 | `risk-service/RiskController.java:49` | Generic `RuntimeException` for simulated failure; no semantic distinction |
| M19 | `risk-service/RiskControllerTest.java` | No tests for invalid inputs, null fields, or error paths |
| M20 | `notification-service/V2_migration.sql:1` | `event_id` column is nullable; allows null duplicates bypassing idempotency |
| M21 | `notification-service/OrderEventConsumer.java:32` | No `@Transactional` wrapping check + save |
| M22 | `notification-service/application.yml` | Missing Kafka consumer tuning: `max.poll.records`, `max.poll.interval.ms` |
| M23 | `notification-service/OrderEventConsumer.java:56` | No failure counter; failed events invisible to monitoring |
| M24 | `notification-service/OrderEventConsumerIntegrationTest.java` | No DB cleanup between tests; shared state |
| M25 | `notification-service/OrderEventConsumerIntegrationTest.java:58-76` | Duplicate event test may be vacuously true (timing dependent) |
| M26 | `docker/grafana/dashboards/*.json` | Missing `schemaVersion` and `id: null`; `histogram_quantile` missing `by (le)` aggregation |
| M27 | `docker/otel-collector-config.yaml` | No `memory_limiter` processor; OOM risk under heavy trace load |
| M28 | `docker/promtail-config.yaml:6` | Positions file at `/tmp/` — lost on restart; re-ingests logs |
| M29 | `docker/promtail-config.yaml:11-21` | No filter for infrastructure logs; Loki ingests its own logs (feedback loop) |
| M30 | `docker/docker-compose.yml` (multiple) | Missing health checks on otel-collector, tempo, prometheus, loki, grafana |
| M31 | `docker/docker-compose.prod.yml:94` | Anonymous Grafana access enabled in prod (role=Viewer) |
| M32 | `infra/aws/aws-setup.sh` | IAM policy `Resource: *` for ECR; not scoped. Script not fully idempotent. |
| M33 | `scripts/load-test.js:59` | Custom `textSummary` suppresses default k6 output |
| M34 | `pom.xml` (parent) | No `pluginManagement` for Jib; no `maven-enforcer-plugin` |
| M35 | `README.md:256` | Load test command targets port 8081 not exposed in prod compose |

---

## Low Severity Issues

| # | Service/File | Description |
|---|-------------|-------------|
| L1 | `order-service/OrderResponse.java:16` | `from()` is package-private; should be public |
| L2 | `order-service/Order.java:15` | `@Setter` exposes `id` and `createdAt` mutators |
| L3 | `order-service/OrderControllerTest.java:29` | `@MockBean` deprecated in Spring Boot 3.4+ |
| L4 | `order-service/OrderController.java:25-28` | Missing `Location` header on POST 201 |
| L5 | `order-service/application.yml:19-21` | No Kafka producer batching config (linger.ms, acks) |
| L6 | `order-service/logback-spring.xml` | No human-readable logs for local dev (JSON only) |
| L7 | `order-service/pom.xml:62` | logstash-logback-encoder version hardcoded (not in parent) |
| L8 | All services | Inconsistent metric naming convention across services |
| L9 | `risk-service/` | Health endpoint could show details for operational visibility |
| L10 | `notification-service/OrderEventConsumer.java:25-28` | Counter tag hardcoded; no failure counter |
| L11 | `notification-service/Notification.java:22` | `@Column` missing `nullable = false` on eventId |
| L12 | `notification-service/pom.xml:18-20` | `spring-boot-starter-web` with no REST controllers |
| L13 | `notification-service/pom.xml:61-64` | `springdoc-openapi` dependency with no REST controllers |
| L14 | `order-service/OrderService.java:39-55` | Timer wraps external call; can't distinguish DB vs network time |
| L15 | `docker/loki-config.yaml` | No retention/compaction policy; logs grow unbounded |
| L16 | `infra/aws/ec2-setup.sh:22-23` | Docker Compose version not pinned (uses `/latest/`) |
| L17 | `infra/aws/ec2-setup.sh` | No swap configured; 4GB RAM may OOM with 12 containers |
| L18 | `scripts/deploy.sh:51,53-54` | Redundant SCP; docker-compose.prod.yml copied twice |

---

## Recommended Remediation Plan

### Phase A — Critical fixes (do first)
1. **Fix Kafka event pipeline reliability (C1, C2, C3, H5, H9)**
   - Order-service: at minimum, make `kafkaTemplate.send().get()` synchronous and let exceptions propagate. Set `acks: all`. Add `NewTopic` bean.
   - Notification-service: remove broad catch, configure `DefaultErrorHandler` with DLT, add `@Transactional`.
   - Long-term: implement Transactional Outbox pattern.

2. **Gate chaos behind config flag (C4)**
   - Add `chaos.enabled` property (default `false`). Use `@ConditionalOnProperty` or simple if-check.

3. **Fix Tempo storage path (C5)**
   - Change docker-compose volume mount: `tempo-data:/var/tempo`.

### Phase B — High severity (before any real deployment)
4. **Add authentication (H1)** — `spring-boot-starter-security` + JWT.
5. **Externalize prod secrets (H2, H15)** — Remove hardcoded passwords from prod compose.
6. **Add input validation to risk-service (H3, H4)** — validation dependency + exception handler.
7. **Add restart policies + resource limits (H11, H12)** — Both compose files.
8. **Add batch processor to OTel Collector (H13)**.
9. **Add database indexes (H8)** — V2 migration for orders table.
10. **Fix deploy script security (H16)** — `StrictHostKeyChecking=accept-new`.
11. **Tag images with version, not `:latest` (H10)**.

### Phase C — Medium severity (improve quality)
12. Fix Grafana dashboard PromQL queries (M26) — add `by (le)` to `histogram_quantile`.
13. Add missing health checks to infra containers (M30).
14. Add failure metrics/counters (M15, M23).
15. Improve test coverage — Kafka publishing, pagination, circuit breaker, error paths (M8-M11, M19, M24-M25).
16. Fix Promtail positions persistence and log filtering (M28, M29).
17. Add catch-all exception handler to order-service (M4).
18. Add page size limit to `listOrders` (M5).

### Phase D — Low severity (nice-to-have)
19. Rename metric `orders.created.total` → `orders.created` (M14).
20. Convert order status to enum (M7).
21. Add human-readable log profile for local dev (L6).
22. Remove unused dependencies from notification-service (L12, L13).
23. Centralize dependency versions in parent POM (L7, M34).
