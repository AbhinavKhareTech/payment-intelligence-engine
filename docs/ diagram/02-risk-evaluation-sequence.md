# 02-risk-evaluation-sequence.md

```mermaid
sequenceDiagram
    autonumber
    participant PG as Payment Gateway
    participant K_IN as payment.txn
    participant Engine as Risk Engine
    participant HC as Claude API
    participant Rules as Rules Engine
    participant K_OUT as decisions / review.q

    PG->>K_IN: Publish transaction event
    K_IN->>Engine: Consume (parallel 3 partitions)
    Engine->>HC: Get / increment velocity + profile
    HC-->>Engine: Data <1ms
    Engine->>Engine: Build structured prompt
    Engine->>HC: POST /v1/messages (with API key)

    alt Circuit Breaker CLOSED
        HC-->>Engine: {risk_score: 0.0–1.0, reasoning, signals}
    else Circuit Breaker OPEN
        Engine->>Engine: Execute fallback heuristic
    end

    Note over Engine: <10 ms deterministic path

    Engine->>Rules: Evaluate rules 001 → 009
    Rules-->>Engine: List of fired rules + suggested decision
    Engine->>Engine: Apply override logic<br>suggested decision<br>(DECLINE > REVIEW > APPROVE)
    Engine->>K_OUT: Publish final decision + full audit trail

    K_IN->>K_IN: Commit offset
