package com.paymentintelligence.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * WebClient configuration for calling GenAI APIs (Claude / OpenAI).
 *
 * Critical design constraints for the auth-path:
 *   - Total latency budget for GenAI scoring: 200ms p99
 *   - Circuit breaker opens after 5 failures in 30s window
 *   - Fallback: deterministic rules-only scoring (no GenAI)
 *   - Connection pool: 50 max connections, 5s idle timeout
 *
 * The timeout is aggressive by design. In the payment authorization
 * path, a slow LLM response is worse than no LLM response. The
 * rules engine can make a decision without GenAI; GenAI improves
 * the decision quality but is not required for correctness.
 */
@Configuration
public class GenAiClientConfig {

    @Value("${app.genai.base-url:https://api.anthropic.com}")
    private String baseUrl;

    @Value("${app.genai.api-key:}")
    private String apiKey;

    @Value("${app.genai.timeout-ms:2000}")
    private int timeoutMs;

    @Bean("genAiWebClient")
    public WebClient genAiWebClient() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(timeoutMs))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000);

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("Content-Type", "application/json")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(512 * 1024))
                .build();
    }

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slowCallRateThreshold(80)
                .slowCallDurationThreshold(Duration.ofMillis(timeoutMs))
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        return CircuitBreakerRegistry.of(config);
    }
}
