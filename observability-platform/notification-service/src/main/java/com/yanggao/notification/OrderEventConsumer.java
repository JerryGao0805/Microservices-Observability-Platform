package com.yanggao.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventConsumer {

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

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
            log.info("Notification sent: orderId={} eventId={}", notification.getOrderId(), eventId);
        } catch (Exception e) {
            log.error("Failed to process OrderCreatedEvent", e);
        }
    }
}
