package com.yanggao.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class OrderEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Counter kafkaPublishSuccessCounter;
    private final Counter kafkaPublishFailureCounter;

    public OrderEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper, MeterRegistry registry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.kafkaPublishSuccessCounter = Counter.builder("orders.kafka.publish")
                .tag("result", "success").register(registry);
        this.kafkaPublishFailureCounter = Counter.builder("orders.kafka.publish")
                .tag("result", "failure").register(registry);
    }

    @Bean
    public NewTopic ordersCreatedTopic() {
        return new NewTopic("orders.created", 3, (short) 1);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        publishOrderCreated(event.order());
    }

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
                            "riskScore", order.getRiskScore() != null ? order.getRiskScore() : 0
                    )
            );
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send("orders.created", order.getId().toString(), payload).get();
            kafkaPublishSuccessCounter.increment();
            log.info("Published OrderCreatedEvent for orderId={}", order.getId());
        } catch (Exception e) {
            kafkaPublishFailureCounter.increment();
            log.error("Failed to publish OrderCreatedEvent for orderId={}", order.getId(), e);
            throw new RuntimeException("Failed to publish Kafka event", e);
        }
    }
}
