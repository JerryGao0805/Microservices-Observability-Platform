package com.yanggao.order;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Component
@Slf4j
public class RiskClient {

    private final RestClient restClient;

    public RiskClient(@Value("${services.risk.url}") String riskServiceUrl) {
        this.restClient = RestClient.builder().baseUrl(riskServiceUrl).build();
    }

    @CircuitBreaker(name = "riskService", fallbackMethod = "evaluateFallback")
    public RiskEvaluationResponse evaluate(BigDecimal amount, String currency) {
        log.info("Calling risk service for amount={} currency={}", amount, currency);
        return restClient.post()
                .uri("/api/risk/evaluate")
                .body(new RiskEvaluationRequest(amount, currency))
                .retrieve()
                .body(RiskEvaluationResponse.class);
    }

    private RiskEvaluationResponse evaluateFallback(BigDecimal amount, String currency, Exception e) {
        log.warn("Risk service unavailable: {}", e.getMessage());
        throw new ServiceUnavailableException("Risk service is currently unavailable");
    }

    public record RiskEvaluationRequest(BigDecimal amount, String currency) {}

    public record RiskEvaluationResponse(int riskScore, String riskLevel) {}
}
