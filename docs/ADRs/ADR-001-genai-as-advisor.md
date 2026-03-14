# ADR-001: GenAI as Advisor, Rules Engine as Decision-Maker

## Status

Accepted

## Context

The system needs to leverage GenAI (LLMs) for transaction risk scoring while operating in a regulated payment environment where every decline must be explainable to auditors and regulators.

Two architectures were considered:

1. **GenAI as decision-maker:** The LLM produces the final Accept/Review/Decline decision directly. The rules engine validates but does not override.
2. **GenAI as advisor:** The LLM produces a risk score and reasoning. A deterministic rules engine makes the final decision using the GenAI score as one input among several.

## Decision

We chose option 2: GenAI as advisor, rules engine as decision-maker.

## Rationale

- **Regulatory explainability:** Payment network rules (Visa, Mastercard) and financial regulators (RBI, FCA, FinCEN) require that decline decisions be traceable to specific, articulable reasons. "The model said so" is not an acceptable explanation in a dispute investigation. Each rule in the rules engine maps to a specific compliance requirement.

- **Operational safety:** LLMs can hallucinate. A model that returns a risk score of 0.01 for a transaction from a sanctioned entity must not result in an APPROVE. Hard rules (sanctions list, blocked merchant, prohibited MCC) must override any model output.

- **Graceful degradation:** When the GenAI API is unavailable (timeout, rate limit, outage), the system must continue making decisions. With GenAI as advisor, the rules engine operates independently with deterministic fallback scoring. With GenAI as decision-maker, an outage would require a complete fallback architecture.

- **Threshold tunability:** The GenAI decline threshold (0.85) and review threshold (0.6) are configuration parameters that compliance and risk teams can adjust without code changes. This separation of policy from mechanism is not possible when the model owns the decision.

## Consequences

- GenAI scoring latency is additive but not blocking. If the circuit breaker opens, decision quality degrades slightly but system availability is unaffected.
- The rules engine must be maintained alongside the GenAI model. Rule changes require configuration updates; model improvements require prompt engineering.
- False positive rates may be higher than a pure ML approach because deterministic rules are conservative by design. This is an acceptable trade-off in a regulated environment.
