---
Kubernetes Deployment Topology
---

```mermaid
graph TD
    A[Ingress / API Gateway] --> B[Service<br>ClusterIP / LoadBalancer]

    B --> C[Deployment<br>risk-engine]

    C --> HPA[Horizontal Pod Autoscaler<br>min 3 – max 12 pods<br>scales on CPU 70% and Memory 80%]

    C --> POD1[Pod 1]
    C --> POD2[Pod 2]
    C --> PODn[... up to 12 pods]

    POD1 --> HZ[Hazelcast Cluster<br>embedded mode<br>automatic discovery]

    POD1 --> KAFKA[Kafka Cluster<br>bootstrap servers from config]

    POD1 --> CLAUDE[Anthropic Claude API<br>via mounted secret]

    subgraph "Scaling & Resilience"
        HPA -.-> C
    end

    classDef ingress fill:#fff3e0,stroke:#ef6c00
    classDef k8s fill:#e3f2fd,stroke:#1976d2
    classDef pod fill:#e8f5e9,stroke:#2e7d32
    classDef external fill:#f3e5f5,stroke:#7b1fa2

    class A ingress
    class B,C,HPA k8s
    class POD1,POD2,PODn pod
    class HZ,KAFKA,CLAUDE external
