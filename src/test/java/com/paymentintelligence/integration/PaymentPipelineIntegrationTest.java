package com.paymentintelligence.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentintelligence.model.PaymentTransaction;
import com.paymentintelligence.model.PaymentTransaction.TransactionChannel;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that boots the full Spring context with a real Kafka
 * broker (via Testcontainers) and validates the end-to-end pipeline.
 *
 * GenAI is disabled in this test profile to avoid external API dependencies.
 * The deterministic fallback is exercised instead.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.genai.enabled=false",
                "app.kafka.consumer.concurrency=1"
        }
)
@Testcontainers
class PaymentPipelineIntegrationTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("REST endpoint should return risk decision for valid transaction")
    void restEndpointWorks() {
        PaymentTransaction txn = new PaymentTransaction(
                "INT-TXN-001", "MERCH-INT-001", "5411", "Integration Test Merchant",
                BigDecimal.valueOf(250), "USD", "VISA", "9999",
                "US", "US", TransactionChannel.CARD_PRESENT,
                false, "fp-int-test", "127.0.0.1", Instant.now(), Map.of());

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/evaluate", txn, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"decision\"");
        assertThat(response.getBody()).contains("INT-TXN-001");
    }

    @Test
    @DisplayName("Score-only endpoint should return risk score")
    void scoreEndpointWorks() {
        PaymentTransaction txn = new PaymentTransaction(
                "INT-TXN-002", "MERCH-INT-002", "5411", "Test",
                BigDecimal.valueOf(8000), "USD", "VISA", "1111",
                "US", "GB", TransactionChannel.ECOMMERCE,
                false, "fp-int-2", "10.0.0.1", Instant.now(), Map.of());

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/score", txn, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"score\"");
        assertThat(response.getBody()).contains("\"risk_signals\"");
    }

    @Test
    @DisplayName("Health endpoint should return UP")
    void healthEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Prometheus metrics endpoint should be accessible")
    void prometheusEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("risk_scoring");
    }

    @Test
    @DisplayName("Kafka producer should be able to send to transactions topic")
    void kafkaProducerWorks() throws Exception {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class
        );

        KafkaTemplate<String, PaymentTransaction> template =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));

        PaymentTransaction txn = new PaymentTransaction(
                "INT-TXN-003", "MERCH-INT-003", "5411", "Kafka Test",
                BigDecimal.valueOf(100), "USD", "VISA", "2222",
                "US", "US", TransactionChannel.CARD_PRESENT,
                false, "fp-kafka", "1.2.3.4", Instant.now(), Map.of());

        template.send(new ProducerRecord<>("payment.transactions", txn.transactionId(), txn));
        template.flush();

        // Give consumer time to process
        Thread.sleep(3000);

        // Verify via metrics that the pipeline processed something
        ResponseEntity<String> metrics = restTemplate.getForEntity(
                "/actuator/prometheus", String.class);
        assertThat(metrics.getBody()).contains("pipeline_transactions_ingested");
    }
}
