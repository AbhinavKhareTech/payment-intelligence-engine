---
Authorization Path Latency Budget (p99 targets)
---

```mermaid
flowchart LR
    subgraph "Total E2E Budget: < 300 ms p99"
        direction LR

        Start((Start)) --> A["Kafka consume + enrich<br>~20 ms"]
        A --> B["Hazelcast lookup<br>~5 ms"]
        B --> C["GenAI call (Claude)<br>~200 ms p99<br>(aggressive timeout)"]
        C --> D["Fallback heuristic (if CB open)<br>< 10 ms"]
        B --> E["Rules engine evaluation<br>~15 ms"]
        E --> F["Publish decision + commit offset<br>~10–15 ms"]

        subgraph "Critical Path (GenAI enabled)"
            B --> C --> E
        end

        subgraph "Fallback Path (Circuit open)"
            B --> D --> E
        end

        F --> End((End<br>< 300 ms total))
    end

    classDef step fill:#e3f2fd,stroke:#1976d2
    classDef critical fill:#fff3e0,stroke:#ef6c00
    classDef fallback fill:#f3e5f5,stroke:#7b1fa2

    class A,B,E,F step
    class C critical
    class D fallback
    class Start,End circle
