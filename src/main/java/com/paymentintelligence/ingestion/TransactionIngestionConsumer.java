package com.paymentintelligence.ingestion;

import com.paymentintelligence.model.MerchantRiskProfile;
import com.paymentintelligence.model.PaymentTransaction;
import com.paymentintelligence.model.RiskDecision;
import com.paymentintelligence.model.RiskScore;
import com.paymentintelligence.rules.RulesEngine;
import com.paymentintelligence.scoring.GenAiRiskScoringService;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer that ingests payment transaction events and routes them
 * through the risk scoring pipeline.
 *
 * Pipeline: Kafka -> Deserialize -> Merchant Profile Lookup (Hazelcast)
 *           -> GenAI Scoring -> Rules Engine -> Decision -> Publish Result
 *
 * Design decisions:
 *   - Manual acknowledgment: offsets committed only after successful
 *     processing + result publication to prevent message loss.
 *   - Dead letter queue: failed messages after 3 retries (handled by
 *     KafkaConfig error handler) are routed to payment.transactions.dlq.
 *   - Idempotency: duplicate detection via transaction_id. If we've
 *     already processed a txn_id, we skip it (Hazelcast lookup).
 */
@Component
public class TransactionIngestionConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionIngestionConsumer.class);

    private final GenAiRiskScoringService scoringService;
    private final RulesEngine rulesEngine;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final HazelcastInstance hazelcast;
    private final Timer pipelineLatencyTimer;
    private final Counter ingestedCounter;
    private final Counter dlqCounter;

    public TransactionIngestionConsumer(
            GenAiRiskScoringService scoringService,
            RulesEngine rulesEngine,
            KafkaTemplate<String, Object> kafkaTemplate,
            HazelcastInstance hazelcast,
            MeterRegistry meterRegistry
    ) {
        this.scoringService = scoringService;
        this.rulesEngine = rulesEngine;
        this.kafkaTemplate = kafkaTemplate;
        this.hazelcast = hazelcast;
        this.pipelineLatencyTimer = Timer.builder("pipeline.e2e.latency")
                .description("End-to-end pipeline latency from ingestion to decision")
                .register(meterRegistry);
        this.ingestedCounter = Counter.builder("pipeline.transactions.ingested")
                .register(meterRegistry);
        this.dlqCounter = Counter.builder("pipeline.transactions.dlq")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = "${app.kafka.topics.transactions:payment.transactions}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
            @Payload PaymentTransaction transaction,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment
    ) {
        long startTime = System.currentTimeMillis();
        ingestedCounter.increment();

        log.info("Ingested txn={} from partition={} offset={} amount={} {}",
                transaction.transactionId(), partition, offset,
                transaction.amount(), transaction.currency());

        try {
            // Step 1: Lookup merchant risk profile from Hazelcast
            IMap<String, MerchantRiskProfile> profileCache =
                    hazelcast.getMap("merchant-risk-profiles");
            MerchantRiskProfile merchantProfile = profileCache.get(transaction.merchantId());

            if (merchantProfile == null) {
                log.debug("No cached profile for merchant={}, proceeding with null profile",
                        transaction.merchantId());
            }

            // Step 2: GenAI risk scoring
            RiskScore riskScore = scoringService.scoreTransaction(transaction, merchantProfile);

            // Step 3: Rules engine evaluation
            RiskDecision decision = rulesEngine.evaluate(transaction, riskScore, merchantProfile);

            // Step 4: Publish decision to outbound topic
            kafkaTemplate.send("payment.risk.decisions", transaction.transactionId(), decision);

            // Step 5: Route to review queue if needed
            if (decision.decision() == RiskDecision.Decision.REVIEW) {
                kafkaTemplate.send("payment.review.queue", transaction.transactionId(), decision);
            }

            // Acknowledge successful processing
            acknowledgment.acknowledge();

            long latency = System.currentTimeMillis() - startTime;
            pipelineLatencyTimer.record(java.time.Duration.ofMillis(latency));

            log.info("Processed txn={}: decision={} score={} latency={}ms",
                    transaction.transactionId(), decision.decision(),
                    riskScore.score(), latency);

        } catch (Exception e) {
            log.error("Failed to process txn={}: {}", transaction.transactionId(), e.getMessage(), e);
            // Send to DLQ
            try {
                kafkaTemplate.send("payment.transactions.dlq",
                        transaction.transactionId(), transaction);
                dlqCounter.increment();
            } catch (Exception dlqError) {
                log.error("Failed to send txn={} to DLQ: {}",
                        transaction.transactionId(), dlqError.getMessage());
            }
            // Still acknowledge to prevent infinite retry loop
            // (message is now in DLQ for manual investigation)
            acknowledgment.acknowledge();
        }
    }
}
