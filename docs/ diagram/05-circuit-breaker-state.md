---
title: Resilience4j Circuit Breaker - States & Transitions
---

```mermaid
stateDiagram-v2
    direction LR

    [*] --> Closed

    Closed --> Open       : failure rate ≥ 50% (sliding window)
    Open   --> HalfOpen   : after wait duration (default 60s)
    HalfOpen --> Closed   : success threshold met
    HalfOpen --> Open     : failure during probe

    Closed   --> Closed   : success / acceptable failure rate
    Open     --> Open     : still in cooldown

    note right of Closed
        • GenAI calls are allowed
        • Normal production path
        • Metrics: success rate, latency
    end note

    note right of Open
        • GenAI calls blocked
        • Immediate deterministic fallback
        • Heuristic scoring used (< 10 ms)
        • Metrics: circuit_open_total
    end note

    note right of HalfOpen
        • Limited test calls to GenAI
        • Trying to recover
        • One failure → back to Open
        • Several successes → Closed
    end note

    legend right
        Closed    : GenAI allowed (healthy)
        Open      : GenAI blocked → fallback
        HalfOpen  : probing for recovery
    end legend
