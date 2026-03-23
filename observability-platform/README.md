# Microservices Observability Platform

A production-grade observable microservices system demonstrating distributed tracing, metrics collection, and log aggregation. Built with Java 21, Spring Boot 3.3, Apache Kafka, and the full Grafana observability stack.

## Architecture

```
  Client (curl/Postman)
         │
         ▼
  ┌─────────────┐    REST     ┌─────────────┐
  │Order Service │───────────▶│Risk Service  │
  │   :8081      │            │   :8082      │
  └──────┬───────┘            └──────────────┘
         │ Kafka
         ▼
  ┌─────────────┐
  │    Kafka    │
  │   (Kraft)   │
  └──────┬──────┘
         │
         ▼
  ┌───────────────┐
  │ Notification  │
  │  Service :8083│
  └───────────────┘

  ┌──────────────────────────────────────────┐
  │          Observability Stack              │
  │                                          │
  │  OTel Agent ──▶ OTel Collector ──▶ Tempo │
  │  Micrometer ──▶ Prometheus               │
  │  Logback JSON ──▶ Promtail ──▶ Loki      │
  │                                          │
  │            Grafana :3000                  │
  │     (Traces + Metrics + Logs)            │
  └──────────────────────────────────────────┘
```

### Data Flow

**Synchronous (REST):** Client → Order Service → Risk Service → risk score → save order → return 201

**Asynchronous (Kafka):** Order Service → `orders.created` topic → Notification Service → save notification

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.3.5 |
| Build | Maven (multi-module) + Google Jib |
| Messaging | Apache Kafka 3.7 (Kraft mode) |
| Database | PostgreSQL 16 (per-service instances) |
| Migrations | Flyway |
| Resilience | Resilience4j Circuit Breaker |
| API Docs | springdoc-openapi (Swagger UI) |
| Tracing | OpenTelemetry Java Agent → OTel Collector → Tempo |
| Metrics | Micrometer → Prometheus |
| Logging | Logback JSON (logstash-encoder) → Promtail → Loki |
| Dashboards | Grafana 11.1 |
| Testing | JUnit 5, Mockito, Testcontainers |
| Container | Docker Compose |

## Quick Start

### Prerequisites

- Java 21
- Maven 3.9+
- Docker & Docker Compose

### One-Command Setup

```bash
# Clone the repo
git clone <repo-url> && cd observability-platform

# Build Docker images
mvn compile com.google.cloud.tools:jib-maven-plugin:3.4.4:dockerBuild

# Start everything
cd docker && docker compose up -d
```

Wait ~30 seconds for all services to initialize, then:

| Service | URL |
|---------|-----|
| Grafana | http://localhost:3000 (auto-login, no password needed) |
| Order Service API | http://localhost:8081/swagger-ui.html |
| Risk Service API | http://localhost:8082/swagger-ui.html |
| Prometheus | http://localhost:9090 |
| Tempo | http://localhost:3200 |

### Create Your First Order

```bash
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":"demo-user","amount":500,"currency":"USD"}'
```

Response:
```json
{
  "id": "a1b2c3d4-...",
  "userId": "demo-user",
  "amount": 500,
  "currency": "USD",
  "status": "APPROVED",
  "riskScore": 50,
  "createdAt": "2026-03-23T..."
}
```

### Seed Sample Data

```bash
bash scripts/seed-data.sh
```

This creates 20 orders with randomized amounts and currencies to populate the dashboards.

## API Reference

### Order Service (port 8081)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/orders` | Create an order |
| `GET` | `/api/orders/{id}` | Get order by ID |
| `GET` | `/api/orders?page=0&size=20` | List orders (paginated) |

**Create Order Request:**
```json
{
  "userId": "string (required)",
  "amount": 100.00,
  "currency": "USD"
}
```

### Risk Service (port 8082)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/risk/evaluate` | Evaluate risk for an amount |

Risk tiers: `<$100` → LOW (10), `$100-$1000` → MEDIUM (50), `>$1000` → HIGH (90, rejected)

Built-in chaos: 20% chance of 500ms delay, 5% chance of failure (triggers circuit breaker).

### Notification Service (port 8083)

Consumes `orders.created` Kafka events automatically. Implements idempotent processing via `eventId` deduplication.

## Observability

### Three Pillars

**Distributed Tracing** — OpenTelemetry Java Agent auto-instruments all HTTP calls, Kafka produce/consume, and JDBC queries. Traces flow through the OTel Collector into Grafana Tempo. A single order creation produces a trace spanning all 3 services.

**Metrics** — Micrometer exposes both standard (JVM, HTTP, Kafka) and custom business metrics via Prometheus:

| Metric | Service | Type |
|--------|---------|------|
| `orders_total` | Order Service | Counter |
| `orders_processing_duration_seconds` | Order Service | Timer |
| `risk_evaluations_total{level}` | Risk Service | Counter |
| `risk_score_distribution` | Risk Service | Distribution |
| `notifications_sent_total{channel}` | Notification Service | Counter |

**Structured Logging** — All services output JSON logs (via logstash-logback-encoder) with traceId/spanId fields. Promtail ships Docker container logs to Loki. Grafana links logs ↔ traces via traceId.

### Grafana Dashboards

Four pre-provisioned dashboards:

1. **System Overview** — Service health, request rates, JVM memory, HTTP latency p99
2. **Order Flow** — Order processing duration percentiles, risk score breakdown, error rates
3. **Trace Explorer** — Tempo trace search + Loki log stream (correlated by service)
4. **Kafka Consumer Lag** — Consumer lag, fetch latency, consumption rates

### Cross-Signal Correlation

Grafana datasources are pre-configured with cross-referencing:
- **Tempo → Loki**: Click a trace span → see matching logs
- **Loki → Tempo**: Click a traceId in logs → jump to the trace

## Project Structure

```
observability-platform/
├── pom.xml                              # Parent POM (multi-module)
├── order-service/                       # REST API + Kafka producer
│   ├── pom.xml
│   └── src/
├── risk-service/                        # Stateless risk evaluation
│   ├── pom.xml
│   └── src/
├── notification-service/                # Kafka consumer + DB
│   ├── pom.xml
│   └── src/
├── docker/
│   ├── docker-compose.yml               # Full local stack (12 containers)
│   ├── docker-compose.prod.yml          # Production (ECR images)
│   ├── otel-collector-config.yaml
│   ├── tempo-config.yaml
│   ├── prometheus.yml
│   ├── loki-config.yaml
│   ├── promtail-config.yaml
│   ├── otel-agent/                      # OTel Java Agent JAR
│   └── grafana/
│       ├── provisioning/                # Datasources + dashboard provider
│       └── dashboards/                  # 4 JSON dashboards
├── infra/aws/
│   ├── ec2-setup.sh                     # EC2 bootstrap (Docker install)
│   └── aws-setup.sh                     # Full AWS infra (ECR, SG, ALB, EC2)
├── scripts/
│   ├── deploy.sh                        # Build → push ECR → deploy EC2
│   ├── load-test.js                     # k6 load test
│   └── seed-data.sh                     # Sample data generator
└── README.md
```

## AWS Deployment

### Infrastructure

Single EC2 t3.medium running all containers via Docker Compose. Grafana exposed publicly through an Application Load Balancer. All internal ports (Kafka, PostgreSQL, service APIs) are not publicly accessible.

### Deploy

```bash
# 1. Create AWS infrastructure (ECR, EC2, ALB, Security Groups, IAM)
cd infra/aws
bash aws-setup.sh <YOUR_PUBLIC_IP>

# 2. SSH into EC2 and install Docker
ssh -i observability-platform-key.pem ec2-user@<EC2_IP>
bash ec2-setup.sh
exit

# 3. Build, push to ECR, and deploy
bash scripts/deploy.sh

# 4. Access Grafana
open http://<ALB_DNS>
```

### Load Test

```bash
# Install k6: https://k6.io/docs/get-started/installation/
k6 run -e BASE_URL=http://<EC2_IP>:8081 scripts/load-test.js
```

Targets: >5000 requests, <5% error rate, p95 latency <500ms.

## Running Tests

```bash
# All tests (unit + integration with Testcontainers)
mvn test

# Single module
mvn test -pl order-service
```

Tests include:
- **Unit tests**: Service layer logic, controller validation, mock-based
- **Integration tests**: Full Spring Boot context with Testcontainers (real PostgreSQL + Kafka)
- **Idempotency test**: Duplicate Kafka events processed only once

## Stopping

```bash
cd docker
docker compose down        # Stop containers (keep data)
docker compose down -v     # Stop + delete all data
```

## License

MIT
