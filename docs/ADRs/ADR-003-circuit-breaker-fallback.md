# ADR-003: Circuit Breaker with Deterministic Fallback for GenAI Resilience

## Status

Accepted

## Context

The GenAI risk scoring layer calls an external LLM API (Anthropic Claude) over HTTPS. This introduces a hard dependency on an external service in the transaction authorization path. External API calls can fail due to rate limits, network issues, API outages, or latency spikes.

In payment systems, availability trumps accuracy. A failed or slow authorization response is worse than a slightly less accurate one. The system must continue making decisions even when the LLM API is completely unavailable.

## Decision

We wrap every GenAI API call in a Resilience4j circuit breaker with an aggressive configuration and provide a deterministic fallback scoring path that activates instantly when the circuit opens.

**Circuit breaker parameters:**

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Sliding window size | 10 calls | Small window to detect failures fast |
| Failure rate threshold | 50% | Open after 5 of 10 calls fail |
| Slow call threshold | 80% | Open if most calls exceed timeout |
| Slow call duration | 2000ms | Aligned with GenAI timeout |
| Wait in open state | 30s | Give the API time to recover |
| Half-open permitted calls | 3 | Probe recovery cautiously |

**Deterministic fallback:**

A weighted heuristic model that evaluates cross-border indicators, card-not-present status, transaction amount, merchant risk tier, chargeback rate, and merchant age. The fallback produces the same `RiskScore` interface as the GenAI path, so the rules engine is agnostic to which path produced the score.

## Rationale

- **Zero-downtime degradation:** When the circuit opens, the fallback activates in under 1ms. No transactions queue, no timeouts propagate to merchants, no authorization failures surface to the payment gateway. The decision quality degrades slightly, but availability is 100%.

- **Self-healing:** The circuit breaker automatically probes recovery after 30 seconds. If the API recovers, scoring transparently returns to GenAI mode. No manual intervention or restart is required.

- **Observable:** Micrometer metrics expose `risk.scoring.success` vs. `risk.scoring.fallback` counters and circuit breaker state. The Grafana dashboard shows these in real time, so the operations team knows immediately when the system is running in degraded mode.

- **Aggressive timeout by design:** The 2-second timeout is aggressive for an LLM call. This is intentional. In the authorization path, a 2-second delay is already unacceptable for real-time processing. If the LLM cannot respond within 2 seconds, the fallback's instant response is strictly better for the merchant experience.

## Consequences

- During GenAI outages, the system operates at reduced scoring accuracy. The deterministic fallback has a higher false positive rate than the GenAI model because it cannot reason about contextual patterns. This is an acceptable trade-off.
- The fallback heuristic weights must be maintained and tuned separately from the GenAI prompt. If fraud patterns shift, both the prompt and the fallback weights may need updating.
- The circuit breaker configuration should be reviewed quarterly with the fraud operations team. If the GenAI API's reliability improves significantly, the thresholds can be relaxed. If it degrades, they should be tightened.
