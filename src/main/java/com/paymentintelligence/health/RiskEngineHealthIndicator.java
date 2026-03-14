package com.paymentintelligence.health;

import com.hazelcast.core.HazelcastInstance;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Custom health indicator that validates connectivity to Kafka and Hazelcast.
 * Used by Kubernetes liveness/readiness probes and by the observability stack.
 */
@Component("riskEngineHealth")
public class RiskEngineHealthIndicator implements HealthIndicator {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final HazelcastInstance hazelcast;

    public RiskEngineHealthIndicator(
            KafkaTemplate<String, Object> kafkaTemplate,
            HazelcastInstance hazelcast
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.hazelcast = hazelcast;
    }

    @Override
    public Health health() {
        boolean kafkaHealthy = checkKafka();
        boolean hazelcastHealthy = checkHazelcast();

        if (kafkaHealthy && hazelcastHealthy) {
            return Health.up()
                    .withDetails(Map.of(
                            "kafka", "connected",
                            "hazelcast", "connected",
                            "hazelcast_cluster_size", hazelcast.getCluster().getMembers().size(),
                            "merchant_profiles_cached",
                            hazelcast.getMap("merchant-risk-profiles").size()
                    ))
                    .build();
        }

        Health.Builder builder = Health.down();
        if (!kafkaHealthy) builder.withDetail("kafka", "disconnected");
        if (!hazelcastHealthy) builder.withDetail("hazelcast", "disconnected");
        return builder.build();
    }

    private boolean checkKafka() {
        try {
            kafkaTemplate.getProducerFactory().createProducer().metrics();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkHazelcast() {
        try {
            return hazelcast.getLifecycleService().isRunning();
        } catch (Exception e) {
            return false;
        }
    }
}
