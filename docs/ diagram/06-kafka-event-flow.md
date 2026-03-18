---
Kafka Topics & Event Flow
---

```mermaid
graph LR
    A[Payment Gateway / Producer] -->|publish txn event| TXNS["Kafka Topic<br>payment.txns<br>key = merchantId<br>3 partitions"]

    TXNS --> CON["Risk Engine<br>Kafka Consumer<br>3 parallel partitions<br>manual ACK + DLQ handling"]

    CON --> DEC["Kafka Topic<br>decisions<br>APPROVE / DECLINE events"]

    CON --> REV["Kafka Topic<br>review.q<br>REVIEW cases + context"]

    CON --> DLQ["Kafka DLQ Topic<br>payment.transactions.dlq<br>poison-pill / error messages"]

    REV --> B[Ops / Compliance Team<br>→ Manual review queue<br>→ L2 analysts / alerting]

    classDef producer fill:#fff3e0,stroke:#ef6c00
    classDef topic fill:#e3f2fd,stroke:#1976d2
    classDef consumer fill:#e8f5e9,stroke:#2e7d32
    classDef downstream fill:#f3e5f5,stroke:#7b1fa2

    class A producer
    class TXNS,DEC,REV,DLQ topic
    class CON consumer
    class B downstream
