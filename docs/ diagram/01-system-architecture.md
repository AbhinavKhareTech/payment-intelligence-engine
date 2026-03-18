# 01-system-architecture.md

## Risk Engine High-Level Architecture

```mermaid
---
title: Risk Engine - System Architecture & Flow Types
---
flowchart TD
    subgraph "External Systems"
        PGW[Payment Gateway]
        CLAUDE[Claude API]
        OPS[Ops / Monitoring]
    end

    subgraph "Kafka Topics"
        K_IN((payment.txn incoming events))
        K_OUT((decisions / review.q))
        K_DLQ((dead-letter / errors))
        K_REV((review.q))
        K_DEC((approved/declined))
    end

    subgraph "Risk Engine"
        direction TB

        CON((CON Consumer))
        CACHE((CACHE Redis / in-memory))
        ENRICH((ENRICH Velocity + Profile))
        GENAI((GENAI Claude Prompt))
        CB((CB Circuit Breaker))
        FALLBACK((FALLBACK Heuristic Rules))
        RULES((RULES Deterministic Rules 001–009))
        DECISION((DECISION Override & Final Score))
    end

    %% Flows
    PGW -->|"Publish txn event"| K_IN
    K_IN -->|"Consume (3 partitions)"| CON
    CON --> CACHE
    CACHE --> ENRICH
    ENRICH --> GENAI
    GENAI --> CB
    CB -->|"CLOSED → success"| GENAI
    CB -->|"OPEN → fallback"| FALLBACK
    GENAI --> RULES
    FALLBACK --> RULES
    RULES --> DECISION
    DECISION -->|"APPROVE / DECLINE"| K_DEC
    DECISION -->|"REVIEW"| K_REV
    DECISION -->|"processing error"| K_DLQ

    %% Styling
    classDef kafka fill:#e3f2fd,stroke:#1976d2,color:#0d47a1
    classDef engine fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20
    classDef external fill:#fff3e0,stroke:#ef6c00,color:#663300

    class K_IN,K_OUT,K_DLQ,K_REV,K_DEC kafka
    class CON,CACHE,ENRICH,GENAI,CB,FALLBACK,RULES,DECISION engine
    class PGW,CLAUDE,OPS external

    %% Cleaner link style (optional)
    linkStyle default stroke:#555,stroke-width:1.2px
