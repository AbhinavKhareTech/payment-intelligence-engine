# Payment Intelligence Engine

GenAI-Augmented Real-Time Payment Risk Intelligence Engine  
Reference architecture for secure, low-latency transaction decisioning — inspired by production needs in global payment acceptance platforms (e.g., fraud/risk pre-auth filters). GenAI-augmented payment processing microservice that performs real-time transaction risk scoring, combining LLM-powered fraud analysis with deterministic business rules to produce Accept / Review / Decline decisions.

Built to demonstrate production-grade payment system architecture: event-driven ingestion, sub-300ms authorization-path latency, circuit-breaker-protected GenAI integration, distributed caching for merchant risk profiles, and full observability.

## Architecture

```
                              +-------------------+
                              |   Kafka Topic     |
                              | payment.txns      |
                              +---------+---------+
                                        |
                                        v
                         +-----------------------------+
                         |  Transaction Ingestion      |
                         |  (Kafka Consumer, 3 parts)  |
                         +-------------+---------------+
                                       |
                    +------------------+------------------+
                    |                                     |
                    v                                     v
         +--------------------+               +---------------------+
         |  Hazelcast Cache   |               |  GenAI Risk Scoring |
         |  Merchant Profiles |               |  (Claude API)       |
         |  Velocity Counters |               |  Circuit Breaker    |
         +--------+-----------+               |  Deterministic      |
                  |                           |  Fallback           |
                  |                           +----------+----------+
                  |                                      |
                  +---------------+----------------------+
                                  |
                                  v
                       +---------------------+
                       |    Rules Engine      |
                       |  9 Deterministic     |
                       |  Rules + GenAI       |
                       |  Score Thresholds    |
                       +---------+-----------+
                                 |
                    +------------+------------+
                    |            |            |
                    v            v            v
               APPROVE       REVIEW       DECLINE
                    |            |            |
                    v            v            v
              +----------+ +----------+ +----------+
              | Kafka:   | | Kafka:   | | Kafka:   |
              | decisions| | review.q | | decisions|
              +----------+ +----------+ +----------+
```

## Tech Stack

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Runtime | Java 21, Spring Boot 3.3 | Core application framework |
| Streaming | Apache Kafka | Transaction event ingestion and decision output |
| Caching | Hazelcast 5.4 (embedded) | Sub-ms merchant profile lookups with near-cache |
| GenAI | Anthropic Claude API | LLM-powered risk scoring with structured output |
| Resilience | Resilience4j | Circuit breaker for GenAI calls |
| API Docs | SpringDoc OpenAPI | Swagger UI at `/swagger-ui.html` |
| Metrics | Micrometer + Prometheus | Custom risk scoring and pipeline metrics |
| Dashboards | Grafana | Pre-built risk engine dashboard |
| Testing | JUnit 5, Testcontainers | Integration tests with real Kafka broker |
| Container | Docker, Docker Compose | Full local development stack |
| Orchestration | Kubernetes (Helm) | Production deployment with HPA |
| CI/CD | GitHub Actions | Build, test, security scan, Docker push |

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker and Docker Compose

### Run locally (no GenAI)

```bash
# Start infrastructure (Kafka, Prometheus, Grafana)
make docker-up

# Run the application with GenAI disabled
make run
```

### Run with GenAI enabled

```bash
export GENAI_API_KEY=your-anthropic-api-key

make docker-up
make run-with-genai
```

### Test the API

```bash
# Evaluate a transaction
make api-evaluate

# Check health
make api-health

# Open Swagger UI
open http://localhost:8080/swagger-ui.html

# Open Grafana dashboard
open http://localhost:3000  # admin/admin
```

### Run tests

```bash
# Unit tests
make test

# Integration tests (requires Docker for Testcontainers)
make test-integration

# Coverage report
make coverage
```

### Produce a sample Kafka message

```bash
make kafka-produce-sample

# Watch decisions
make kafka-consume-decisions
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/evaluate` | Full risk evaluation: GenAI score + rules engine |
| `POST` | `/api/v1/score` | GenAI risk score only (no rules) |
| `GET` | `/api/v1/merchant/{id}/profile` | Retrieve cached merchant risk profile |
| `PUT` | `/api/v1/merchant/{id}/profile` | Upsert merchant risk profile |
| `GET` | `/actuator/health` | Health check (Kafka + Hazelcast status) |
| `GET` | `/actuator/prometheus` | Prometheus metrics |
| `GET` | `/swagger-ui.html` | OpenAPI documentation |

## Risk Scoring Pipeline

### 1. GenAI Scoring Layer

The LLM receives structured transaction context (amount, channel, merchant profile, cross-border indicators) and returns a risk score between 0.0 and 1.0 with chain-of-thought reasoning suitable for regulatory audit trails.

The GenAI call is wrapped in a Resilience4j circuit breaker. If the breaker opens (50% failure rate over a 10-call sliding window), the system falls back to deterministic heuristic scoring. The deterministic fallback evaluates cross-border status, card-not-present risk, transaction amount, and merchant risk tier to produce a score.

**Latency budget:** 200ms p99 for GenAI scoring. The timeout is aggressive by design. In the authorization path, a slow LLM response is worse than no LLM response.

### 2. Rules Engine

Nine deterministic rules evaluated in priority order:

| Rule | Trigger | Action |
|------|---------|--------|
| RULE-001 | Merchant in BLOCKED state | DECLINE |
| RULE-002 | Prohibited MCC (gambling, etc.) | DECLINE |
| RULE-003 | Chargeback rate > 2% | REVIEW |
| RULE-004 | Amount > $10,000 | REVIEW |
| RULE-005 | High-risk MCC + cross-border | REVIEW |
| RULE-006 | Daily velocity > 500 txns | REVIEW |
| RULE-007 | Daily volume > $1M | REVIEW |
| RULE-008 | New merchant (< 30 days) + high value | REVIEW |
| RULE-009 | GenAI score >= thresholds | REVIEW / DECLINE |

A single DECLINE from any rule overrides GenAI. A REVIEW from any rule escalates the transaction even if GenAI scores it as low risk. GenAI improves the decision quality but is never the sole decision-maker.

## Observability

### Metrics (Prometheus)

- `risk.scoring.latency` - GenAI scoring latency histogram
- `risk.scoring.success` / `risk.scoring.fallback` - GenAI vs deterministic path
- `risk.decision{decision=APPROVE|REVIEW|DECLINE}` - Decision distribution
- `pipeline.e2e.latency` - Full pipeline latency (ingestion to decision)
- `pipeline.transactions.ingested` - Throughput counter
- `pipeline.transactions.dlq` - Dead letter queue counter
- Circuit breaker state and metrics via Resilience4j

### Grafana Dashboard

Pre-configured dashboard at `http://localhost:3000` with panels for decision distribution, p50/p95/p99 latency, GenAI success vs fallback rates, DLQ alerts, and circuit breaker state.

## Kubernetes Deployment

```bash
# Deploy with Helm
helm install risk-engine ./k8s \
  --set secrets.genaiApiKey=$GENAI_API_KEY \
  --set env.KAFKA_BOOTSTRAP_SERVERS=kafka-headless:9092

# HPA configured: 3 min, 12 max replicas
# Scales on CPU (70%) and memory (80%)
```

## Design Decisions

See `docs/ARCHITECTURE.md` for the full design document and `docs/ADRs/` for individual Architecture Decision Records:

- **ADR-001:** GenAI as advisor, rules engine as decision-maker
- **ADR-002:** Hazelcast embedded mode over Redis for auth-path latency
- **ADR-003:** Circuit breaker with deterministic fallback for GenAI resilience

## Project Structure

```
payment-intelligence-engine/
├── src/main/java/com/paymentintelligence/
│   ├── api/             # REST controllers + OpenAPI
│   ├── config/          # Kafka, Hazelcast, GenAI client configs
│   ├── exception/       # Global error handling
│   ├── health/          # Custom health indicators
│   ├── ingestion/       # Kafka consumer pipeline
│   ├── model/           # Domain objects (records)
│   ├── rules/           # Deterministic rules engine
│   └── scoring/         # GenAI risk scoring service
├── src/test/
│   ├── .../rules/       # RulesEngine unit tests
│   ├── .../scoring/     # Scoring service unit tests
│   ├── .../api/         # Controller MockMvc tests
│   └── .../integration/ # Testcontainers integration tests
├── k8s/                 # Helm chart (deployment, HPA, service)
├── observability/       # Prometheus + Grafana configs + dashboards
├── docs/                # ARCHITECTURE.md + ADRs
├── .github/workflows/   # CI/CD pipeline
├── docker-compose.yml   # Local dev stack
├── Dockerfile           # Multi-stage production build
├── Makefile             # Developer shortcuts
└── pom.xml              # Maven build with all dependencies
```

## License

MIT
