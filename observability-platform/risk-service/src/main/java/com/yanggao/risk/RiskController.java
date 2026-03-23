package com.yanggao.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/risk")
@Slf4j
public class RiskController {

    public record RiskEvaluationRequest(BigDecimal amount, String currency) {}

    public record RiskEvaluationResponse(int riskScore, String riskLevel) {}

    @PostMapping("/evaluate")
    public RiskEvaluationResponse evaluate(@RequestBody RiskEvaluationRequest request) {
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
        return new RiskEvaluationResponse(score, level);
    }
}
