---
Decision Routing Logic
---

```mermaid
flowchart TD
    A[Final Decision from Rules Engine] --> B{Is DECLINE?}

    B -->|Yes| C[Publish to<br>decisions topic]
    B -->|No| D{Is REVIEW?}

    D -->|Yes| E[Publish to<br>review.q topic<br>with full context + signals]
    D -->|No| C

    E --> F[L1 Auto-review<br>low-severity cases]
    E --> G[L2 Analyst Queue<br>manual review SLA]
    E --> H[Compliance / STR<br>high-risk or regulatory match]

    C --> I[Downstream consumers<br>e.g. notification, ledger, gateway response]

    classDef decision fill:#ffebee,stroke:#c62828
    classDef publish fill:#e3f2fd,stroke:#1976d2
    classDef route fill:#e8f5e9,stroke:#2e7d32

    class B,D decision
    class C,E publish
    class F,G,H route
