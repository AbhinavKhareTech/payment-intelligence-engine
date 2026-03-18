---
Hazelcast Embedded Cache Structure
---

```mermaid
classDiagram
    direction TB

    class MerchantRiskProfile {
        +String merchantId
        +String riskTier (LOW / MEDIUM / HIGH)
        +double currentChargebackRate
        +int daysSinceOnboarded
        +Map<String, Integer> mccVelocityLast24h
    }

    class VelocityCounter {
        +String key (e.g. merchantId:1h-window)
        +long count
        +double amountSum
        +long lastResetTimestamp
    }

    class NearCacheConfig {
        +int timeToLiveSeconds = 300
        +int maxSize = 100000
        +String evictionPolicy = "LRU"
    }

    MerchantRiskProfile "1" --> "0..*" VelocityCounter : "tracks velocity for"
    NearCacheConfig "*" --> MerchantRiskProfile : "applied to"
    NearCacheConfig "*" --> VelocityCounter : "applied to"

    note for MerchantRiskProfile "Stored in Hazelcast IMap~String, MerchantRiskProfile~<br>near-cache enabled for sub-ms reads"
    note for VelocityCounter "Stored in Hazelcast IMap~String, VelocityCounter~<br>used for real-time rate limiting / velocity checks"
    note for NearCacheConfig "Embedded near-cache config<br>reduces network hops during txn processing"
