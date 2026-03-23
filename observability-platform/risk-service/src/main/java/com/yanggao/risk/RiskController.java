package com.yanggao.risk;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api/risk")
@Tag(name = "Risk")
@Slf4j
public class RiskController {

    private final MeterRegistry registry;
    private final DistributionSummary riskScoreDistribution;

    public RiskController(MeterRegistry registry) {
        this.registry = registry;
        this.riskScoreDistribution = DistributionSummary.builder("risk.score.distribution")
                .description("Distribution of risk scores")
                .register(registry);
    }

    public record RiskEvaluationRequest(BigDecimal amount, String currency) {}

    public record RiskEvaluationResponse(int riskScore, String riskLevel) {}

    @PostMapping("/evaluate")
    @Operation(summary = "Evaluate risk for a payment order")
    public RiskEvaluationResponse evaluate(@RequestBody RiskEvaluationRequest request) throws InterruptedException {
        // 20% chance of 500ms delay
        if (ThreadLocalRandom.current().nextDouble() < 0.2) {
            log.info("Simulating slow response for amount={}", request.amount());
            Thread.sleep(500);
        }

        // 5% chance of error
        if (ThreadLocalRandom.current().nextDouble() < 0.05) {
            log.warn("Simulating failure for amount={}", request.amount());
            throw new RuntimeException("Simulated risk service failure");
        }

        int score;
        String level;

        if (request.amount().compareTo(new BigDecimal("100")) < 0) {
            score = 10;
            level = "LOW";
        } else if (request.amount().compareTo(new BigDecimal("1000")) <= 0) {
            score = 50;
            level = "MEDIUM";
        } else {
            score = 90;
            level = "HIGH";
        }

        log.info("Risk evaluated: amount={} currency={} score={} level={}",
                request.amount(), request.currency(), score, level);

        Counter.builder("risk.evaluations.total")
                .tag("level", level)
                .register(registry)
                .increment();
        riskScoreDistribution.record(score);

        return new RiskEvaluationResponse(score, level);
    }
}
