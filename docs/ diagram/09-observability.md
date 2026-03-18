---
Observability – Metrics & Dashboard Flow
---

```mermaid
graph TD
    A[Risk Engine Application] -->|exposes| B["/actuator/prometheus<br>Micrometer metrics endpoint"]

    B --> C[Prometheus<br>scrape job<br>every 15s]

    C --> D[Grafana<br>datasource connected<br>to Prometheus]

    D --> E[Pre-built Dashboard Panels]

    E --> P1["Decision distribution<br>APPROVE / REVIEW / DECLINE<br>pie chart + time series"]
    E --> P2["Latency: p50 / p95 / p99<br>full pipeline + GenAI only<br>histogram + gauge"]
    E --> P3["GenAI path usage<br>success % vs fallback %<br>stacked bar"]
    E --> P4["Circuit breaker state<br>transitions over time<br>state timeline"]
    E --> P5["DLQ messages<br>count + rate<br>alert threshold line"]

    subgraph "Key Metrics Collected"
        M1["risk.scoring.latency (histogram)"]
        M2["risk.decision APPROVE/REVIEW/DECLINE (counter)"]
        M3["risk.genai.fallback (counter)"]
        M4["circuit_breaker_state (gauge)"]
        M5["pipeline.transactions.dlq (counter)"]
    end

    A -.-> M1 & M2 & M3 & M4 & M5

    classDef app fill:#e8f5e9,stroke:#2e7d32
    classDef infra fill:#e3f2fd,stroke:#1976d2
    classDef panel fill:#fff3e0,stroke:#ef6c00

    class A app
    class B,C,D infra
    class P1,P2,P3,P4,P5 panel
    class M1,M2,M3,M4,M5 panel
