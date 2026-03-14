package com.paymentintelligence.config;

import com.hazelcast.config.*;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hazelcast configuration for distributed caching of merchant risk profiles.
 *
 * Why Hazelcast over Redis:
 *   - Embedded mode eliminates a network hop on reads (sub-ms latency).
 *   - Near-cache gives each node a local L1 backed by distributed L2.
 *   - Native Java serialization avoids the JSON ser/de overhead in the
 *     hot path (authorization latency budget is < 50ms total).
 *
 * The merchant-risk-profiles map uses a 5-minute TTL. Profiles are
 * refreshed asynchronously from the analytics pipeline. Stale profiles
 * are acceptable for a short window because the rules engine applies
 * deterministic fallback rules for missing/expired profiles.
 */
@Configuration
public class HazelcastConfig {

    @Value("${app.hazelcast.cluster-name:payment-intel-cluster}")
    private String clusterName;

    @Value("${app.hazelcast.merchant-cache-ttl-seconds:300}")
    private int merchantCacheTtlSeconds;

    @Bean
    public Config hazelcastConfig() {
        Config config = new Config();
        config.setClusterName(clusterName);

        // Merchant risk profile cache
        MapConfig merchantProfileCache = new MapConfig("merchant-risk-profiles");
        merchantProfileCache.setTimeToLiveSeconds(merchantCacheTtlSeconds);
        merchantProfileCache.setMaxIdleSeconds(600);
        merchantProfileCache.setEvictionConfig(
                new EvictionConfig()
                        .setEvictionPolicy(EvictionPolicy.LRU)
                        .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
                        .setSize(50_000)
        );

        // Near cache for local reads
        NearCacheConfig nearCacheConfig = new NearCacheConfig();
        nearCacheConfig.setTimeToLiveSeconds(60);
        nearCacheConfig.setMaxIdleSeconds(30);
        nearCacheConfig.setInMemoryFormat(InMemoryFormat.OBJECT);
        merchantProfileCache.setNearCacheConfig(nearCacheConfig);

        config.addMapConfig(merchantProfileCache);

        // Transaction velocity cache (sliding window counters)
        MapConfig velocityCache = new MapConfig("transaction-velocity");
        velocityCache.setTimeToLiveSeconds(3600); // 1-hour window
        velocityCache.setEvictionConfig(
                new EvictionConfig()
                        .setEvictionPolicy(EvictionPolicy.LRU)
                        .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
                        .setSize(200_000)
        );
        config.addMapConfig(velocityCache);

        // Network config: default to multicast discovery for local dev,
        // Kubernetes discovery plugin for production
        NetworkConfig networkConfig = config.getNetworkConfig();
        JoinConfig joinConfig = networkConfig.getJoin();
        joinConfig.getMulticastConfig().setEnabled(true);

        return config;
    }

    @Bean
    public HazelcastInstance hazelcastInstance(Config hazelcastConfig) {
        return Hazelcast.newHazelcastInstance(hazelcastConfig);
    }
}
