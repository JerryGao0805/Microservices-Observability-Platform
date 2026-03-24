package com.yanggao.risk;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api/risk")
@Tag(name = "Risk")
@Slf4j
public class RiskController {

    private final DistributionSummary riskScoreDistribution;
    private final Counter lowRiskCounter;
    private final Counter mediumRiskCounter;
    private final Counter highRiskCounter;
    private final boolean chaosEnabled;

    public RiskController(MeterRegistry registry,
                          @Value("${chaos.enabled:false}") boolean chaosEnabled) {
        this.chaosEnabled = chaosEnabled;
        this.riskScoreDistribution = DistributionSummary.builder("risk.score.distribution")
                .description("Distribution of risk scores")
                .register(registry);
        this.lowRiskCounter = Counter.builder("risk.evaluations.total")
                .tag("level", "LOW").register(registry);
        this.mediumRiskCounter = Counter.builder("risk.evaluations.total")
                .tag("level", "MEDIUM").register(registry);
        this.highRiskCounter = Counter.builder("risk.evaluations.total")
                .tag("level", "HIGH").register(registry);
    }

    public record RiskEvaluationRequest(
            @NotNull @Positive BigDecimal amount,
            String currency
    ) {}

    public record RiskEvaluationResponse(int riskScore, String riskLevel) {}

    @PostMapping("/evaluate")
    @Operation(summary = "Evaluate risk for a payment order")
    public RiskEvaluationResponse evaluate(@Valid @RequestBody RiskEvaluationRequest request) {
        if (chaosEnabled) {
            // 20% chance of 500ms delay
            if (ThreadLocalRandom.current().nextDouble() < 0.2) {
                log.info("Simulating slow response for amount={}", request.amount());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // 5% chance of error
            if (ThreadLocalRandom.current().nextDouble() < 0.05) {
                log.warn("Simulating failure for amount={}", request.amount());
                throw new ChaosException("Simulated risk service failure");
            }
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

        switch (level) {
            case "LOW" -> lowRiskCounter.increment();
            case "MEDIUM" -> mediumRiskCounter.increment();
            case "HIGH" -> highRiskCounter.increment();
        }
        riskScoreDistribution.record(score);

        return new RiskEvaluationResponse(score, level);
    }

    // --- Exception Handlers (H4, M4-equivalent for risk-service) ---

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message",
                        e.getDefaultMessage() != null ? e.getDefaultMessage() : "invalid"))
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", "Validation failed",
                "details", fieldErrors,
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Internal server error",
                "timestamp", Instant.now().toString()
        ));
    }
}
