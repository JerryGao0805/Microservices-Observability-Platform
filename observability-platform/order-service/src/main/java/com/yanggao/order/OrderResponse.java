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
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus() != null ? order.getStatus().name() : null,
                order.getRiskScore(),
                order.getCreatedAt()
        );
    }
}
