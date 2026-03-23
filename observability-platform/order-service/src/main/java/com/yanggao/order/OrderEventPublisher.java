package com.yanggao.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishOrderCreated(Order order) {
        try {
            var event = Map.of(
                    "specversion", "1.0",
                    "id", UUID.randomUUID().toString(),
                    "source", "order-service",
                    "type", "com.yanggao.order.created",
                    "time", Instant.now().toString(),
                    "data", Map.of(
                            "orderId", order.getId().toString(),
                            "userId", order.getUserId(),
                            "amount", order.getAmount(),
                            "currency", order.getCurrency(),
                            "riskScore", order.getRiskScore()
                    )
            );
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send("orders.created", order.getId().toString(), payload);
            log.info("Published OrderCreatedEvent for orderId={}", order.getId());
        } catch (Exception e) {
            log.error("Failed to publish OrderCreatedEvent for orderId={}", order.getId(), e);
        }
    }
}
