# 05-circuit-breaker-state.md

## Circuit Breaker States & Transitions

This diagram shows the state machine for the circuit breaker protecting Claude API calls (GENAI prompt step).  
It prevents cascading failures during timeouts, rate limits, or outages.

```mermaid
---
title: Circuit Breaker State Machine (Claude API Protection)
---
stateDiagram-v2
    direction LR

    [*] --> Closed

    state Closed {
        [*] --> NormalOperation
    }

    Closed --> Open : "Failure threshold exceeded<br/>(e.g., >50% errors in window,<br/>or consecutive timeouts)"

    Open --> HalfOpen : "Wait timeout expires<br/>(configurable, e.g., 30s–5min)"

    state HalfOpen {
        [*] --> TestCalls
    }

    HalfOpen --> Closed : "Success threshold met<br/>(e.g., N consecutive successes<br/>or low error rate in permitted calls)"

    HalfOpen --> Open : "Any failure during test<br/>(even one bad response resets)"

    Open --> Open : "Remains open during cooldown<br/>(fallback heuristic used)"

    Closed --> Closed : "Normal: Requests pass through<br/>(Claude called, velocity/profile fetched)"

    %% Notes / annotations
    note right of Closed
        Normal operation.<br/>
        All calls to Claude succeed → count successes.
    end note

    note right of Open
        Fast-fail mode.<br/>
        No calls to Claude.<br/>
        Execute fallback heuristic immediately.
    end note

    note right of HalfOpen
        Recovery phase.<br/>
        Allow limited test calls (e.g., 3–10).<br/>
        Monitor closely.
    end note

    %% Styling (optional, works in most renderers)
    classDef closed fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20
    classDef open fill:#ffebee,stroke:#c62828,color:#b71c1c
    classDef halfopen fill:#fff3e0,stroke:#ef6c00,color:#ef6c00

    class Closed closed
    class Open open
    class HalfOpen halfopen
