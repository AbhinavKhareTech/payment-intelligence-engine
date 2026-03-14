# ADR-002: Hazelcast Embedded Mode Over Redis

## Status

Accepted

## Context

The system requires sub-millisecond lookups of merchant risk profiles during transaction scoring. These profiles are updated asynchronously (every 5 minutes) by an analytics pipeline and are read on every transaction in the authorization path.

Options evaluated:

1. **Redis (standalone or cluster):** Industry-standard distributed cache. Requires a network hop per read. Typical latency: 0.1-0.5ms on the same host, 1-3ms cross-host.
2. **Hazelcast (embedded mode):** JVM-embedded distributed cache. Near-cache provides local L1 reads with zero network hops. Distributed L2 provides consistency across pods.
3. **Local ConcurrentHashMap with periodic refresh:** Simplest option. No consistency across pods.

## Decision

We chose Hazelcast in embedded mode with near-cache.

## Rationale

- **Authorization-path latency:** Every millisecond in the auth path compounds across millions of transactions. A local near-cache read (sub-microsecond) vs. a Redis network call (0.3ms average) saves 300 microseconds per transaction. At 10K TPS, that is 3 seconds of aggregate latency saved per second.

- **Near-cache with distributed backup:** The near-cache provides local-speed reads with eventual consistency from the distributed map. A 60-second near-cache TTL means a pod may serve a slightly stale profile for up to 60 seconds after an update. This is acceptable because merchant risk profiles change slowly (minutes to hours) and the rules engine applies fallback logic for stale or missing profiles.

- **No additional infrastructure:** Embedded Hazelcast requires no separate cache server deployment. The cache scales with the application pods. This reduces operational complexity compared to managing a Redis cluster.

- **Native Java serialization:** Hazelcast stores Java objects natively in the JVM heap (for near-cache) and uses optimized serialization for the distributed map. Redis requires JSON or binary serialization on every read/write, adding CPU overhead in the hot path.

## Consequences

- Cache lifecycle is coupled to pod lifecycle. A pod restart means a cold near-cache (warms from distributed backup in seconds).
- Hazelcast cluster discovery must be configured per environment: multicast for local dev, Kubernetes discovery plugin for production.
- Memory overhead: each pod holds a portion of the distributed map plus near-cache entries. Sizing must account for this (the 50K entry limit per node is configured in `HazelcastConfig`).
- If the team later requires features Redis excels at (pub/sub, Lua scripting, sorted sets), a Redis sidecar may be introduced for those specific use cases without replacing Hazelcast for the primary cache.
