# Microservices Observability Demo Platform
## Development Plan v1.0

---

## 1. Project Overview

### Goal
Build a production-grade, observable microservices system that demonstrates end-to-end distributed tracing, metrics collection, and log aggregation — deployable to AWS.

### Business Domain
A simplified **Payment Processing** system (fintech theme, aligned with HSBC background):
- User submits a payment order
- System evaluates risk synchronously
- System notifies downstream services asynchronously via Kafka

### Why This Project
- Directly showcases HSBC + Innovation AI experience in a standalone, demostrable product
- Covers the full observability stack: Traces + Metrics + Logs (the "three pillars")
- Publicly accessible Grafana dashboard as interview demo
- Resume bullet: *"Deployed production-grade observability stack to AWS with OpenTelemetry, Prometheus, Grafana, and Kafka"*

---

## 2. Tech Stack

### Core
| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 17 (LTS) |
| Framework | Spring Boot | 3.3.x |
| Build Tool | Maven | 3.9.x (multi-module) |
| Containerization | Docker + Docker Compose | Latest |

### Services Communication
| Pattern | Technology |
|---------|-----------|
| Synchronous REST | Spring Web + OpenAPI 3 |
| Asynchronous Events | Apache Kafka |
| Service Discovery | Docker DNS (local) / ECS Service Discovery (AWS) |

### Observability Stack
| Pillar | Tool | Role |
|--------|------|------|
| Traces | OpenTelemetry SDK + Collector | Instrument + route traces |
| Traces Storage | Grafana Tempo | Store and query traces |
| Metrics | Micrometer + Prometheus | Collect and store metrics |
| Logs | Logback + Loki | Aggregate structured logs |
| Visualization | Grafana | Unified dashboard (metrics + traces + logs) |

### AWS Infrastructure
| Resource | Purpose |
|----------|---------|
| EC2 t3.medium | Host all containers via Docker Compose |
| Application Load Balancer | Expose Grafana publicly (port 3000) |
| Security Group | Control inbound/outbound traffic |
| ECR | Store Docker images |
| IAM Role | EC2 instance role with least privilege |
| S3 | Store Grafana dashboard backups (optional) |
| CloudWatch | EC2 instance-level monitoring (CPU/memory) |

---

## 3. Architecture

```
                        ┌─────────────────────────────────────────┐
                        │              EC2 t3.medium               │
                        │                                          │
  Client (curl/         │  ┌─────────────┐    REST    ┌─────────┐ │
  Postman/UI)  ────────▶│  │Order Service│───────────▶│  Risk   │ │
                        │  │  :8081      │            │ Service │ │
                        │  └──────┬──────┘            │  :8082  │ │
                        │         │ Kafka              └─────────┘ │
                        │         ▼                                │
                        │  ┌─────────────┐                        │
                        │  │    Kafka    │                        │
                        │  │   :9092     │                        │
                        │  └──────┬──────┘                        │
                        │         │                                │
                        │         ▼                                │
                        │  ┌───────────────┐                      │
                        │  │  Notification │                      │
                        │  │   Service     │                      │
                        │  │    :8083      │                      │
                        │  └───────────────┘                      │
                        │                                          │
                        │  ┌────────────────────────────────────┐ │
                        │  │        Observability Stack          │ │
                        │  │  OTel Collector → Tempo (traces)   │ │
                        │  │  Prometheus (metrics)               │ │
                        │  │  Loki (logs)                        │ │
                        │  │  Grafana :3000 (visualization) ◀───┼─┼── ALB (public)
                        │  └────────────────────────────────────┘ │
                        └─────────────────────────────────────────┘
```

### Data Flow

**Sync path (REST):**
```
POST /api/orders
  → Order Service validates request
  → Order Service calls Risk Service GET /api/risk/evaluate
  → Risk Service returns risk score
  → Order Service saves order with risk status
  → Returns 201 response to client
```

**Async path (Kafka):**
```
Order Service publishes OrderCreatedEvent to topic: orders.created
  → Notification Service consumes event
  → Notification Service logs notification (simulated email/SMS)
  → Publishes NotificationSentEvent to topic: notifications.sent
```

---

## 4. Service Design

### 4.1 Order Service (port 8081)

**Responsibilities:** Accept and manage payment orders

**REST Endpoints:**
```
POST   /api/orders          - Create a new order
GET    /api/orders/{id}     - Get order by ID
GET    /api/orders          - List all orders (paginated)
GET    /actuator/health     - Health check
GET    /actuator/prometheus - Metrics scrape endpoint
```

**Order Entity:**
```json
{
  "id": "uuid",
  "userId": "string",
  "amount": "decimal",
  "currency": "string (USD/EUR/GBP)",
  "status": "PENDING | APPROVED | REJECTED",
  "riskScore": "integer (0-100)",
  "createdAt": "ISO-8601 timestamp"
}
```

**Kafka Producer:** publishes to `orders.created`

**Database:** PostgreSQL (schema: `orders_db`)

---

### 4.2 Risk Service (port 8082)

**Responsibilities:** Evaluate risk score for a given payment order

**REST Endpoints:**
```
POST   /api/risk/evaluate   - Evaluate risk for an order
GET    /actuator/health
GET    /actuator/prometheus
```

**Risk Evaluation Logic (simulated):**
- Amount < $100: risk score 10 (LOW)
- Amount $100–$1000: risk score 50 (MEDIUM)
- Amount > $1000: risk score 90 (HIGH)
- Randomly inject 500ms delay 20% of the time (simulate slow upstream)
- Randomly return error 5% of the time (simulate failure, triggers circuit breaker)

**No database** — stateless service

**Resilience4j:** Circuit breaker on `/api/risk/evaluate`

---

### 4.3 Notification Service (port 8083)

**Responsibilities:** Consume order events and send simulated notifications

**Kafka Consumer:** subscribes to `orders.created`

**REST Endpoints:**
```
GET    /api/notifications           - List sent notifications
GET    /actuator/health
GET    /actuator/prometheus
```

**Notification Entity:**
```json
{
  "id": "uuid",
  "orderId": "string",
  "userId": "string",
  "message": "string",
  "channel": "EMAIL | SMS",
  "sentAt": "ISO-8601 timestamp"
}
```

**Database:** PostgreSQL (schema: `notifications_db`)

---

## 5. Kafka Topics

| Topic | Producer | Consumer | Partitions | Retention |
|-------|----------|----------|------------|-----------|
| `orders.created` | Order Service | Notification Service | 3 | 7 days |
| `notifications.sent` | Notification Service | (future) | 3 | 7 days |

**Event Schema (orders.created):**
```json
{
  "eventId": "uuid",
  "eventType": "ORDER_CREATED",
  "timestamp": "ISO-8601",
  "payload": {
    "orderId": "uuid",
    "userId": "string",
    "amount": "decimal",
    "currency": "string",
    "riskScore": "integer"
  }
}
```

---

## 6. Observability Configuration

### 6.1 OpenTelemetry

- Each Spring Boot service uses `opentelemetry-spring-boot-starter`
- Auto-instrumentation: HTTP requests, Kafka producer/consumer, JDBC calls
- All services export traces to **OTel Collector** at `otel-collector:4317` (gRPC)
- OTel Collector routes:
  - Traces → Grafana Tempo
  - Metrics → Prometheus (via remote write)

**Required trace attributes on every span:**
```
service.name, service.version, deployment.environment
http.method, http.status_code (for HTTP spans)
messaging.kafka.topic (for Kafka spans)
```

### 6.2 Metrics (Prometheus)

Each service exposes `/actuator/prometheus`. Prometheus scrapes every 15s.

**Custom metrics to implement:**
| Service | Metric | Type |
|---------|--------|------|
| Order Service | `orders_created_total` | Counter |
| Order Service | `orders_processing_duration_seconds` | Histogram |
| Risk Service | `risk_evaluations_total{status}` | Counter |
| Risk Service | `risk_score_distribution` | Histogram |
| Notification Service | `notifications_sent_total{channel}` | Counter |

### 6.3 Structured Logging (Loki)

All services use **Logback** with JSON format (logstash-logback-encoder).

**Required fields in every log line:**
```json
{
  "timestamp": "ISO-8601",
  "level": "INFO/ERROR/WARN",
  "service": "order-service",
  "traceId": "...",
  "spanId": "...",
  "message": "...",
  "orderId": "..."   // when applicable
}
```

Promtail agent ships logs from Docker containers to Loki.

### 6.4 Grafana Dashboards

Must build the following dashboards:
1. **System Overview** — all services health, request rate, error rate
2. **Order Flow** — end-to-end order lifecycle, success/failure rates
3. **Distributed Trace Explorer** — linked to Tempo, click through from metrics
4. **Kafka Consumer Lag** — Notification Service lag monitoring

**Alerting rules (in Grafana):**
- Error rate > 5% for any service → alert
- Risk Service p95 latency > 500ms → alert
- Kafka consumer lag > 1000 messages → alert

---

## 7. Project Structure

```
observability-platform/
├── pom.xml                          # Parent POM (multi-module)
├── order-service/
│   ├── pom.xml
│   └── src/
├── risk-service/
│   ├── pom.xml
│   └── src/
├── notification-service/
│   ├── pom.xml
│   └── src/
├── docker/
│   ├── docker-compose.yml           # Full local stack
│   ├── docker-compose.override.yml  # Local dev overrides
│   ├── otel-collector-config.yaml
│   ├── prometheus.yml
│   ├── loki-config.yaml
│   ├── tempo-config.yaml
│   └── grafana/
│       ├── provisioning/
│       └── dashboards/
├── infra/
│   ├── aws/
│   │   ├── ec2-setup.sh             # EC2 bootstrap script
│   │   ├── security-group.tf        # Terraform (optional)
│   │   └── README.md
│   └── ecs/ (Phase 2)
├── scripts/
│   ├── load-test.sh                 # k6 or hey load testing
│   └── seed-data.sh
├── docs/
│   ├── architecture.md
│   └── runbook.md
└── README.md
```

---

## 8. Development Phases

### Phase 1 — Foundation (Week 1-2)

**Tasks:**
- [ ] Initialize Maven multi-module project
- [ ] Scaffold all 3 Spring Boot services (health endpoint only)
- [ ] Set up Docker Compose with PostgreSQL + Kafka
- [ ] Verify inter-service REST communication
- [ ] Verify Kafka producer/consumer end-to-end

**Test Plan:**
1. Cold start: `docker compose down -v && docker compose up` completes without errors
2. All 3 health endpoints respond correctly
3. PostgreSQL reachable from each service that needs it
4. Kafka broker reachable, topic `orders.created` auto-created
5. Order Service successfully calls Risk Service via REST (verify via logs)
6. A manually published Kafka message to `orders.created` is consumed and logged by Notification Service

**Acceptance Criteria:**
```bash
# All containers show "healthy"
docker compose ps   # → all services: Up (healthy)

# Health checks
curl localhost:8081/actuator/health  # → {"status":"UP"}
curl localhost:8082/actuator/health  # → {"status":"UP"}
curl localhost:8083/actuator/health  # → {"status":"UP"}

# Inter-service REST: Order Service log shows successful call to Risk Service
# Kafka: Notification Service log shows message received from orders.created topic
```

---

### Phase 2 — Business Logic (Week 2-3)

**Tasks:**
- [ ] Implement full Order Service CRUD
- [ ] Implement Risk Service evaluation logic + Resilience4j circuit breaker
- [ ] Implement Notification Service Kafka consumer with idempotency
- [ ] OpenAPI/Swagger docs for all REST endpoints
- [ ] Integration tests with Testcontainers

**Test Plan:**
1. Happy path: full order flow end-to-end
2. Risk score tiers: verify each amount bracket maps to correct score
3. Input validation: missing/invalid fields return correct error response
4. Circuit breaker: Risk Service unavailable → Order Service returns fallback gracefully
5. Idempotency: duplicate Kafka event (same `eventId`) processed only once
6. GET endpoints: retrieve existing order, 404 for unknown ID
7. Swagger UI loads and all endpoints are documented

**Acceptance Criteria:**
```bash
# Happy path
POST /api/orders {"userId":"u1","amount":500,"currency":"USD"}
→ 201, body contains riskScore=50, status=APPROVED
→ Notification Service log shows "Notification sent for orderId=..."

# Risk score tiers
amount=50    → riskScore=10, status=APPROVED
amount=500   → riskScore=50, status=APPROVED
amount=2000  → riskScore=90, status=REJECTED

# Validation
POST /api/orders {} → 422, body contains field-level errors for userId, amount, currency

# Circuit breaker
Stop risk-service container → POST /api/orders → 503 with fallback message (not 500 stack trace)
Restart risk-service → subsequent requests succeed (circuit closes)

# Idempotency
Publish same Kafka event (identical eventId) twice → Notification DB has exactly 1 record

# Not found
GET /api/orders/non-existent-id → 404

# Test coverage
mvn verify → BUILD SUCCESS, line coverage ≥ 80% (checked via JaCoCo report)

# Swagger
curl localhost:8081/swagger-ui/index.html → 200
```

---

### Phase 3 — Observability (Week 3-4)

**Tasks:**
- [ ] Add OpenTelemetry Java Agent to all services
- [ ] Configure OTel Collector, Grafana Tempo, Loki, Prometheus
- [ ] Implement all 5 custom metrics
- [ ] Configure structured JSON logging with automatic traceId injection
- [ ] Build all 4 Grafana dashboards
- [ ] Configure 3 alerting rules

**Test Plan:**
1. Distributed trace: single POST /api/orders produces one trace spanning all 3 services
2. Trace-log correlation: traceId from Tempo queryable in Loki logs
3. Custom metrics: all 5 metrics visible and non-zero in Prometheus after requests
4. Log format: every log line contains required JSON fields (traceId, spanId, service, level)
5. Grafana dashboards: all 4 load without errors, panels show data
6. Alerting: force error condition → alert fires within configured window

**Acceptance Criteria:**
```bash
# Distributed trace
POST /api/orders (valid body)
→ Open Grafana Tempo → search by service "order-service"
→ Trace must contain spans from: order-service, risk-service, notification-service
→ Trace duration visible, all spans have status OK

# Trace-log correlation
Copy traceId from Tempo trace
→ Grafana Explore → Loki → query: {service_name="order-service"} | json | traceId="<copied-id>"
→ Logs for that exact request appear

# Custom metrics in Prometheus
curl localhost:9090/api/v1/query?query=orders_created_total          # → value > 0
curl localhost:9090/api/v1/query?query=risk_evaluations_total        # → value > 0
curl localhost:9090/api/v1/query?query=notifications_sent_total      # → value > 0

# Log format (check any service log line)
docker compose logs order-service | tail -5
→ Each line is valid JSON containing: timestamp, level, service, traceId, spanId, message

# Grafana dashboards
Open each dashboard → zero "No data" panels, all panels render correctly:
  - System Overview: shows 3 services, request rate, error rate
  - Order Flow: shows order counts by status
  - Distributed Trace Explorer: Tempo datasource linked, clickable
  - Kafka Consumer Lag: shows lag metric for notification-service-group

# Alerting
Send 20 consecutive bad requests to force error rate > 5%
→ Grafana Alerting → alert "High Error Rate" transitions to Firing within 1 minute
Stop sending errors → alert returns to Normal
```

---

### Phase 4 — AWS Deployment (Week 5)

**Tasks:**
- [ ] Write and validate EC2 bootstrap script on fresh Amazon Linux 2023
- [ ] Configure Security Groups (expose only port 80/443 via ALB)
- [ ] Set up Application Load Balancer → EC2:3000
- [ ] Build and push all images to ECR
- [ ] Deploy full stack on EC2, validate all containers healthy
- [ ] Run load test from local machine against AWS endpoint

**Test Plan:**
1. Bootstrap: run `ec2-setup.sh` on a fresh EC2 instance — must complete without manual intervention
2. Container health: all services healthy within 3 minutes of `docker compose up`
3. Network security: internal ports not reachable from public internet
4. Public access: Grafana accessible via ALB DNS
5. Load test: 50 RPS sustained for 2 minutes
6. Post-load validation: dashboards show meaningful data from the load test

**Acceptance Criteria:**
```bash
# Bootstrap
ssh ec2-user@<EC2-IP>
bash ec2-setup.sh         # exits 0, no manual steps required
docker compose up -d
docker compose ps         # all containers: Up (healthy) within 3 min

# Network security (run from local machine, not EC2)
curl http://<EC2-PUBLIC-IP>:8081/actuator/health  # → connection refused or timeout
curl http://<EC2-PUBLIC-IP>:9092                  # → connection refused or timeout
curl http://<EC2-PUBLIC-IP>:9090                  # → connection refused or timeout

# Public Grafana access
curl -I http://<ALB-DNS>/                         # → 200 OK, Grafana login page

# Load test (k6)
k6 run --vus 10 --duration 2m scripts/load-test.js
→ http_req_failed rate < 5%
→ http_req_duration p95 < 500ms
→ total requests > 5000

# Post-load dashboard check
Prometheus: orders_created_total > 5000
Grafana Order Flow dashboard: spike visible during test window
Grafana Kafka Consumer Lag: lag returns to 0 after test completes
```

---

### Phase 5 — Polish (Week 6)

**Tasks:**
- [ ] Write README with architecture diagram and one-command setup guide
- [ ] Capture Grafana dashboard screenshots for README
- [ ] Write a technical blog post (Medium/personal site)
- [ ] Tag v1.0.0 release on GitHub

**Acceptance Criteria:**
- Fresh clone + `docker compose up` works with zero additional steps (verified on a clean machine or in a new directory)
- README contains: architecture diagram, tech stack table, setup instructions, dashboard screenshots
- GitHub repo has a v1.0.0 tag with release notes summarizing what was built

---

## 9. Coding Standards

### General Principles
1. Use latest stable versions of libraries and idiomatic approaches as of today
2. Keep it simple — NEVER over-engineer, ALWAYS simplify, NO unnecessary defensive programming. No extra features — focus on what we aligned.

### Java / Spring Boot
- Java 17 features encouraged: records, sealed classes, pattern matching
- Spring Boot 3.3.x (Jakarta EE 10, not javax)
- Use `@Slf4j` (Lombok) for logging — never `System.out.println`
- All REST controllers annotated with `@RestController` + `@Tag` (OpenAPI)
- DTOs separate from entities — use records for DTOs
- No business logic in controllers — delegate to `@Service` layer
- `@Transactional` only on service methods, never on controllers

### API Design Rules
- Use plural nouns: `/api/orders` not `/api/order`
- HTTP status codes must be accurate: 201 for create, 404 for not found, 422 for validation errors
- All responses wrapped in consistent envelope:
```json
{
  "data": {...},
  "timestamp": "ISO-8601",
  "traceId": "..."
}
```
- Validation errors return field-level details (use `@Valid` + `@ExceptionHandler`)

### Kafka Rules
- Consumer group IDs: `{service-name}-group` (e.g., `notification-service-group`)
- Always implement idempotent consumers (check for duplicate eventId before processing)
- Use `@KafkaListener` with manual acknowledgment (`AckMode.MANUAL_IMMEDIATE`)
- Dead Letter Topic for failed messages: `{topic-name}.DLT`

### Observability Rules
- Every `@Service` method over 50ms must have a custom metric or span
- Log at entry + exit of every Kafka consumer handler
- Never log sensitive data (amounts in logs are OK, but no user PII)
- TraceId must appear in every log line (auto-injected via MDC with OTel)

### Error Handling
- Global exception handler via `@RestControllerAdvice`
- Resilience4j circuit breaker on all external HTTP calls (Risk Service call from Order Service)
- Kafka consumer retries: 3 attempts with exponential backoff before DLT

---

## 10. Git & Branching Strategy

### Branch Model
```
main          — production-ready, protected
  └── dev     — integration branch
        └── feature/phase-1-foundation
        └── feature/order-service-crud
        └── feature/otel-instrumentation
        └── fix/kafka-consumer-retry
```

### Commit Message Format (Conventional Commits)
```
feat(order-service): add order creation endpoint
fix(risk-service): handle null amount in evaluation
chore(docker): add otel collector config
docs: update architecture diagram
test(notification): add kafka consumer integration test
```

### PR Rules
- All PRs target `dev`, never directly to `main`
- PR title must follow Conventional Commits format
- Must include: what changed, why, how to test
- Squash merge to keep history clean
- Tag `main` with semantic version for each phase completion: `v1.0-phase1`, `v1.0-phase2`...

---

## 11. Testing Strategy

| Test Type | Tool | Target Coverage | Scope |
|-----------|------|-----------------|-------|
| Unit Tests | JUnit 5 + Mockito | 80%+ line coverage | Service layer, pure logic |
| Integration Tests | Testcontainers | All DB + Kafka interactions | Repository, Kafka consumer/producer |
| Contract Tests | Spring Cloud Contract | All REST APIs | Order ↔ Risk API contract |
| Load Tests | k6 or `hey` | N/A | Run before AWS deploy |

### Testcontainers Setup
Each service's integration tests spin up:
- PostgreSQL container (matching prod version)
- Kafka container (for Kafka-dependent services)

No mocking of DB or Kafka in integration tests.

---

## 12. AWS Deployment Rules

### EC2 Setup
- AMI: Amazon Linux 2023
- Instance type: t3.medium (2 vCPU, 4GB RAM)
- Storage: 30GB gp3 EBS
- Region: us-east-1

### Security Group Rules
| Type | Port | Source | Purpose |
|------|------|--------|---------|
| Inbound | 22 | Your IP only | SSH access |
| Inbound | 3000 | ALB Security Group | Grafana (via ALB) |
| Inbound | 80/443 | 0.0.0.0/0 | ALB listener |
| Outbound | All | 0.0.0.0/0 | Egress |

**Ports 8081-8083, 9092 (Kafka), 9090 (Prometheus) must NOT be publicly exposed.**

### IAM Role (EC2 Instance Role)
Permissions (least privilege):
- `ecr:GetAuthorizationToken`
- `ecr:BatchGetImage`
- `ecr:GetDownloadUrlForLayer`
- `s3:PutObject` (for Grafana backup bucket only)
- `cloudwatch:PutMetricData`

### Deployment Process
```bash
# On EC2 (via SSH or EC2 Instance Connect)
git pull origin main
docker compose pull          # Pull latest images from ECR
docker compose up -d         # Zero-downtime rolling restart
docker compose ps            # Verify all containers healthy
```

### Cost Control
- Stop EC2 instance when not demoing (saves ~$0.83/day)
- Set AWS Billing Alert at $30/month
- Use `docker system prune` weekly to reclaim disk

---

## 13. Definition of Done (Per Phase)

**Rule: Never move to the next phase until all defects from the current phase are fixed.**

This applies at every level — per task, per phase. The sequence is always:
```
Implement → Unit Test → Integration Test → Fix all defects → then proceed
```

A phase is complete when ALL of the following are true:
- [ ] All features for the phase are implemented
- [ ] All unit tests pass with ≥ 80% line coverage (`mvn verify`)
- [ ] All integration tests pass (Testcontainers — no mocking of DB or Kafka)
- [ ] All acceptance criteria from the phase's Test Plan are manually verified
- [ ] Zero known defects — all failures fixed before moving on
- [ ] No `ERROR` logs during normal operation
- [ ] Docker Compose starts cleanly from scratch (`docker compose down -v && docker compose up`)
- [ ] Grafana shows expected metrics/traces for the phase
- [ ] Code committed and PR merged to `dev`
- [ ] Phase tag created on `main`

---

## 14. Open Questions / Future Scope

### Decided
- [x] Mono-repo (Maven multi-module)
- [x] PostgreSQL (not H2, even for dev — use Testcontainers)
- [x] AWS EC2 + Docker Compose (Phase 1 deploy)
- [x] No service mesh (Istio/Linkerd) — overkill for demo
- [x] No API Gateway — direct ALB to Grafana only

### Future (Post v1.0)
- Migrate to ECS (Fargate) with ECS Service Discovery
- Add AWS Secrets Manager for DB credentials (replace env vars)
- Add GitHub Actions CI pipeline (build → test → push to ECR)
- Add Chaos Engineering (randomly kill containers, observe alerts)
- Add a simple React frontend to submit orders visually

---

*Last updated: 2026-03-23*
*Status: Planning*
