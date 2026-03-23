package com.yanggao.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String userId,
        BigDecimal amount,
        String currency,
        String status,
        Integer riskScore,
        Instant createdAt
) {
    static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus(),
                order.getRiskScore(),
                order.getCreatedAt()
        );
    }
}
