---
title: Payment Intelligence Engine - System Architecture
---
graph TB
    subgraph "Ingestion Layer"
        K_IN["Kafka Topic<br>payment.txns<br>(3 partitions)"]
    end

    subgraph "Risk Engine (Spring Boot Java 21)"
        CON["Kafka Consumer<br>Manual ACK + DLQ"]
        CACHE["Hazelcast Embedded<br>Near-cache enabled<br>Maps:<br>• merchant-risk-profiles<br>• transaction-velocity-counters"]
        ENRICH["Enrich Transaction<br>+ Merchant Profile"]
        GENAI["GenAI Scoring Service<br>Anthropic Claude<br>Structured JSON Output"]
        CB["Resilience4j Circuit Breaker<br>50% failure / 10-call window"]
        FALLBACK["Deterministic Fallback<br>Heuristic scoring"]
        RULES["Rules Engine<br>9 priority-ordered rules"]
        DECISION["Decision Aggregator<br>Rules override GenAI"]
    end

    subgraph "Output Layer"
        K_DEC["Kafka Topic<br>decisions"]
        K_REV["Kafka Topic<br>review.q"]
        K_DLQ["Kafka DLQ<br>payment.transactions.dlq"]
    end

    subgraph "External Systems"
        CLAUDE["Anthropic Claude API"]
        PGW["Payment Gateway / Orchestrator"]
        OPS["Ops / Compliance Team<br>(review queue consumer)"]
    end

    PGW --> K_IN
    K_IN --> CON
    CON --> ENRICH
    ENRICH --> CACHE
    CACHE --> GENAI
    GENAI --> CB
    CB -->|"CLOSED → success"| GENAI
    CB -->|"OPEN"| FALLBACK
    GENAI --> RULES
    FALLBACK --> RULES
    RULES --> DECISION
    DECISION -->|"APPROVE / DECLINE"| K_DEC
    DECISION -->|"REVIEW"| K_REV
    DECISION -->|"processing error"| K_DLQ

    classDef kafka fill:#e3f2fd,stroke:#1976d2,color:#0d47a1
    classDef engine fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20
    classDef external fill:#fff3e0,stroke:#ef6c00,color:#e65100

    class K_IN,K_DEC,K_REV,K_DLQ kafka
    class CON,CACHE,ENRICH,GENAI,CB,FALLBACK,RULES,DECISION engine
    class CLAUDE,PGW,OPS external
