package com.yanggao.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class OrderEventConsumer {

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    private final Counter notificationsSentCounter;

    public OrderEventConsumer(NotificationRepository notificationRepository,
                              ObjectMapper objectMapper, MeterRegistry registry) {
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
        this.notificationsSentCounter = Counter.builder("notifications.sent.total")
                .tag("channel", "EMAIL")
                .description("Total notifications sent")
                .register(registry);
    }

    @KafkaListener(topics = "orders.created")
    public void onOrderCreated(String event) {
        try {
            JsonNode root = objectMapper.readTree(event);
            String eventId = root.get("id").asText();

            // Idempotency check
            if (notificationRepository.existsByEventId(eventId)) {
                log.info("Duplicate event ignored: eventId={}", eventId);
                return;
            }

            JsonNode data = root.get("data");
            var notification = new Notification();
            notification.setEventId(eventId);
            notification.setOrderId(UUID.fromString(data.get("orderId").asText()));
            notification.setUserId(data.get("userId").asText());
            notification.setMessage("Order %s processed with risk score %d".formatted(
                    data.get("orderId").asText(), data.get("riskScore").asInt()));
            notification.setChannel("EMAIL");

            notificationRepository.save(notification);
            notificationsSentCounter.increment();
            log.info("Notification sent: orderId={} eventId={}", notification.getOrderId(), eventId);
        } catch (Exception e) {
            log.error("Failed to process OrderCreatedEvent", e);
        }
    }
}
