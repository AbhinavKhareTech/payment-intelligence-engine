# 04-rules-engine.md

## Rules Engine - Deterministic Rule Evaluation & Override Logic

```mermaid
---
title: Rules Engine - Rule Evaluation Flow (001–009)
---
flowchart TD
    subgraph "Rules Engine"
        direction TB

        START((Start<br/>From GENAI / Fallback))
        EVAL[Evaluate Rules 001 → 009<br/>in priority order]
        COLLECT["Collect Fired Rules<br/>& Signals"]
        SUGGEST["Determine Suggested Decision<br/>(majority vote or highest priority)"]
        OVERRIDE["Apply Override Logic<br/>Priority: DECLINE > REVIEW > APPROVE"]
        FINAL((Final Decision<br/>+ Audit Trail))
    end

    %% Main flow
    START --> EVAL
    EVAL --> COLLECT
    COLLECT --> SUGGEST
    SUGGEST --> OVERRIDE
    OVERRIDE --> FINAL

    %% Decision branches from override
    OVERRIDE -->|"Any DECLINE fired"| DECLINE[DECLINE]
    OVERRIDE -->|"No DECLINE, any REVIEW"| REVIEW[REVIEW → review.q]
    OVERRIDE -->|"All APPROVE or no rules fired"| APPROVE[APPROVE]

    %% Styling
    classDef rule fill:#fffde7,stroke:#f57f17,color:#5d4037
    classDef decision fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20
    classDef output fill:#e3f2fd,stroke:#1976d2,color:#0d47a1

    class START,EVAL,COLLECT,SUGGEST,OVERRIDE rule
    class DECLINE,REVIEW,APPROVE decision
    class FINAL output

    %% Link styles
    linkStyle default stroke:#555,stroke-width:1.5px
