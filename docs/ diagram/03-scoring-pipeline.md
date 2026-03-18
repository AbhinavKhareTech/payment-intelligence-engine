## Risk Scoring & Decision Pipeline

```mermaid
sequenceDiagram
    autonumber
    participant Engine as Risk Engine
    participant HC as Claude API (GENAI)
    participant Rules as Rules Engine
    participant DEC as Decision Logic
    participant K_OUT as Kafka (decisions / review.q)

    Note over Engine: Start scoring pipeline<br/><1 ms cold path target

    Engine->>HC: "POST structured prompt to /v1/messages<br/>(includes velocity, profile, txn details, API key)"
    
    alt "Circuit Breaker CLOSED (healthy)"
        HC-->>Engine: "Response: {risk_score: 0.0-1.0, reasoning, signals/flags}"
    else "Circuit Breaker OPEN (timeout/failure)"
        Engine->>Engine: "Execute fallback heuristic score<br/>(simple rules-based default)"
    end

    Engine->>Rules: "Evaluate deterministic rules 001 → 009<br/>(velocity checks, amount thresholds, geo, etc.)"
    Rules-->>Engine: "Fired rules list + suggested decision (APPROVE/REVIEW/DECLINE)"

    Engine->>DEC: "Apply override logic<br/>Priority: DECLINE > REVIEW > APPROVE<br/>Final score + audit signals"

    alt "Final decision = DECLINE"
        DEC->>K_OUT: "Publish DECLINE + full audit trail"
    else "Final decision = REVIEW"
        DEC->>K_OUT: "Publish REVIEW + full audit trail → review.q"
    else "Final decision = APPROVE"
        DEC->>K_OUT: "Publish APPROVE + full audit trail"
    end

    Note over DEC,K_OUT: Decision published in <5 ms<br/>Offset committed on consumer
