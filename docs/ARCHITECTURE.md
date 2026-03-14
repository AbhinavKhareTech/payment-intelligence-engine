# Architecture: Payment Intelligence Engine

## 1. Problem Statement

Payment fraud detection at scale faces a fundamental tension: authorization-path latency budgets (sub-50ms for the scoring call within a total 300ms authorization window) are incompatible with the response times of large language models (200ms-2s typical). Meanwhile, traditional rule-based systems produce high false positive rates because they lack the contextual reasoning that fraud analysts apply intuitively.

This system resolves that tension by treating GenAI as an advisory scoring layer that operates within a circuit-breaker envelope, with a deterministic rules engine as the authoritative decision-maker. If GenAI is slow or unavailable, the system degrades gracefully to heuristic scoring without any change in the decision interface.

## 2. Design Principles

**Principle 1: GenAI is a tool, not the decision-maker.**
Every decline must be traceable to a specific rule or threshold. A hallucinating model cannot approve or decline a transaction that violates a hard business constraint. The rules engine always has the final word.

**Principle 2: Availability over accuracy.**
In the authorization path, a fast mediocre decision is better than a slow perfect one. The circuit breaker opens aggressively (50% failure rate over 10 calls), and the deterministic fallback is always ready. Merchants experience zero degradation when GenAI is unavailable.

**Principle 3: Every decision is auditable.**
Regulatory bodies require plain-language explanations for declines. The `RiskDecision` record captures every rule that fired, the GenAI reasoning, the score, and the timestamps. This audit trail survives the entire lifecycle of a dispute investigation.

**Principle 4: Configuration over code for policy changes.**
Risk thresholds, velocity limits, and MCC restrictions are externalized to `application.yml`. A compliance officer can change the decline threshold from 0.85 to 0.80 without a code deployment. The rule engine and the decisioning layer are structurally separated.

## 3. System Context

```
                     External Systems
    +---------------------------------------------+
    |                                             |
    |  Payment Gateway  -->  Kafka Topic          |
    |  (card auth req)      (payment.txns)        |
    |                                             |
    |  Analytics Pipeline --> Hazelcast            |
    |  (merchant profiles)   (merchant-risk-       |
    |                         profiles map)        |
    |                                             |
    |  Anthropic API    <--  Risk Scoring Svc     |
    |  (Claude Sonnet)       (circuit-breaker-    |
    |                         protected call)     |
    |                                             |
    |  Ops Dashboard    <--  Kafka Topic          |
    |  (review queue)       (payment.review.q)    |
    |                                             |
    +---------------------------------------------+
```

The Payment Intelligence Engine sits between the payment gateway and the downstream authorization system. It receives transaction events via Kafka, enriches them with cached merchant profiles from Hazelcast, scores them using GenAI (with deterministic fallback), applies business rules, and publishes the decision to an outbound Kafka topic.

## 4. Component Architecture

### 4.1 Transaction Ingestion (Kafka Consumer)

The Kafka consumer uses manual acknowledgment mode. Offsets are committed only after:
1. The transaction has been scored
2. The decision has been published to the outbound topic
3. Review-queue routing has completed (if applicable)

This guarantees at-least-once processing. Duplicate detection is handled downstream by transaction_id idempotency.

**Consumer configuration:**
- 3 consumer threads (one per partition, assuming 3-partition inbound topic)
- `MAX_POLL_RECORDS=50` to balance throughput with processing latency
- Dead letter queue (`payment.transactions.dlq`) after 3 retries with 1s backoff

**Scaling model:** Add partitions to the inbound Kafka topic and increase `app.kafka.consumer.concurrency` in lockstep. The HPA in Kubernetes scales pod count independently, but each pod's consumer thread count should match its partition assignment.

### 4.2 Merchant Risk Profile Cache (Hazelcast)

**Why Hazelcast over Redis:**

In the authorization path, every network hop matters. Hazelcast in embedded mode eliminates the network round-trip entirely for cached reads. The near-cache configuration gives each JVM a local L1 cache (60s TTL) backed by a distributed L2 (5-minute TTL). For a merchant profile lookup, the hot-path latency is sub-millisecond.

Redis would require a network hop (even on the same host, that is 0.1-0.5ms) plus serialization/deserialization overhead. At 1M+ TPS, that overhead compounds.

**Cache structure:**

| Map | TTL | Max Size | Eviction | Purpose |
|-----|-----|----------|----------|---------|
| `merchant-risk-profiles` | 300s | 50K per node | LRU | Merchant risk tier, chargeback rate, velocity |
| `transaction-velocity` | 3600s | 200K per node | LRU | Sliding window counters per merchant/card |

**Staleness tolerance:** A 5-minute-stale merchant profile is acceptable because the rules engine applies deterministic fallback rules for missing or expired profiles. The GenAI layer also applies its own contextual assessment independent of the cached profile.

### 4.3 GenAI Risk Scoring

The scoring service constructs a structured prompt containing:
- Transaction attributes (amount, channel, MCC, cross-border flag, timestamp)
- Merchant profile (risk tier, chargeback rate, average transaction amount, onboarding age)
- Instruction to return valid JSON with `risk_score`, `reasoning`, and `risk_signals`

The system prompt constrains the model to pure risk assessment and forces JSON-only output. No free-text parsing is required.

**Circuit breaker configuration (Resilience4j):**
- Sliding window: 10 calls
- Failure rate threshold: 50%
- Slow call threshold: 80% (where slow = exceeds 2s)
- Wait duration in open state: 30s
- Half-open: 3 permitted calls

When the circuit opens, all scoring requests are routed to the deterministic fallback. The fallback applies a weighted heuristic model:

| Signal | Weight |
|--------|--------|
| Cross-border | +0.15 |
| Card-not-present / e-commerce | +0.10 |
| High value (> $5,000) | +0.15 |
| RED tier merchant | +0.25 |
| YELLOW tier merchant | +0.10 |
| Chargeback rate > 2% | +0.20 |
| New merchant (< 30 days) | +0.05 |

Scores are capped at 1.0. The base score starts at 0.1.

### 4.4 Rules Engine

The rules engine evaluates in strict priority order. Hard blocks (sanctions, blocked merchants, prohibited MCCs) are evaluated first and produce immediate DECLINE decisions. Soft rules (velocity, volume, high-value) produce REVIEW decisions. GenAI score thresholds are evaluated last.

**Key design decision:** A single DECLINE from any rule overrides all other signals, including a low GenAI score. A REVIEW from any rule escalates the transaction even if GenAI says low risk. This asymmetry is deliberate: hard rules encode regulatory requirements that cannot be overridden by a probabilistic model.

**Review queue routing:**
- `FRAUD_L2`: GenAI score >= 0.75 (high-confidence fraud signal)
- `COMPLIANCE`: Triggered by chargeback, prohibited MCC, or cross-border high-risk rules
- `FRAUD_L1`: All other reviews (default queue)

### 4.5 Observability

Every layer emits Micrometer metrics:

| Metric | Type | Description |
|--------|------|-------------|
| `risk.scoring.latency` | Timer | GenAI call latency histogram |
| `risk.scoring.success` | Counter | Successful GenAI calls |
| `risk.scoring.fallback` | Counter | Deterministic fallback invocations |
| `risk.decision{decision}` | Counter | Decision distribution (APPROVE/REVIEW/DECLINE) |
| `pipeline.e2e.latency` | Timer | Full pipeline latency |
| `pipeline.transactions.ingested` | Counter | Inbound throughput |
| `pipeline.transactions.dlq` | Counter | DLQ messages (operational alert) |

The Grafana dashboard provides real-time visibility into decision distribution, latency percentiles, GenAI health (success vs fallback rate), and DLQ accumulation.

## 5. Failure Modes

| Failure | Impact | Mitigation |
|---------|--------|------------|
| GenAI API timeout | Scoring degrades to deterministic | Circuit breaker opens, fallback activates in < 1ms |
| GenAI API down | Same as timeout | Circuit stays open until recovery (30s check) |
| Kafka broker down | No new transactions ingested | Consumer retries with backoff; existing in-flight processing completes |
| Hazelcast node loss | Temporary cache misses | Distributed backup kicks in; null-profile path in scoring handles gracefully |
| Malformed transaction | Processing fails | Sent to DLQ after 3 retries; offset acknowledged to prevent poison pill |
| GenAI hallucination | Incorrect risk score | Rules engine overrides; hard rules cannot be bypassed by any score |

## 6. Scaling Strategy

### Current: Single Service (1K TPS)

The current architecture handles approximately 1K transactions per second on a single 3-replica deployment with GenAI disabled (deterministic-only mode). With GenAI enabled, throughput depends on LLM API rate limits and latency.

### Phase 2: Partitioned Scoring (10K TPS)

- Increase Kafka partitions to 12
- Scale consumer concurrency to 12 per pod
- Add Hazelcast discovery via Kubernetes plugin (replace multicast)
- Introduce a scoring router that sends only high-value or flagged transactions to GenAI, routing the rest directly to deterministic scoring (reduces GenAI call volume by 60-70%)

### Phase 3: Multi-Region (100K+ TPS)

- Deploy per-region Kafka clusters with MirrorMaker 2 for cross-region replication
- Hazelcast WAN replication for merchant profile consistency
- GenAI scoring becomes async (score-then-update model rather than inline)
- Rules engine remains synchronous and inline (sub-5ms latency at any scale)

## 7. Security Considerations

- GenAI API key stored in Kubernetes secrets, injected via env var (never in code or config files)
- No PII in GenAI prompts (card numbers are masked to last four; cardholder names are excluded)
- Kafka topics encrypted in transit (TLS) in production
- Hazelcast cluster communication encrypted (TLS member-to-member)
- Container runs as non-root user (`appuser`)
- OWASP dependency check in CI pipeline (fails on CVSS >= 9)

## 8. Trade-offs Acknowledged

**GenAI adds latency and cost.** The system is designed so that GenAI can be disabled entirely with a single configuration flag. The deterministic fallback is always production-ready. GenAI improves decision quality but is not required for the system to function correctly.

**Hazelcast embedded mode couples cache lifecycle to application lifecycle.** If a pod restarts, its local cache is cold. Mitigation: near-cache warms in seconds from the distributed backup; the null-profile path in scoring handles the cold-start gracefully.

**Kafka at-least-once means potential duplicate decisions.** Downstream consumers must handle idempotency via `transaction_id`. This is a standard pattern in payment systems and is preferable to the complexity and throughput cost of exactly-once semantics.

**Rule thresholds are static configuration, not ML-tuned.** In a production system, these thresholds would be tuned by a data science team using historical chargeback data. The architecture supports dynamic threshold updates via Spring Cloud Config or a feature flag system without code changes.
