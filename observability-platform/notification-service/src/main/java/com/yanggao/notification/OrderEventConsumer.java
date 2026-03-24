package com.yanggao.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Slf4j
public class OrderEventConsumer {

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    private final Counter notificationsSentCounter;
    private final Counter notificationsFailedCounter;

    public OrderEventConsumer(NotificationRepository notificationRepository,
                              ObjectMapper objectMapper, MeterRegistry registry) {
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
        this.notificationsSentCounter = Counter.builder("notifications.sent.total")
                .tag("channel", "EMAIL")
                .description("Total notifications sent")
                .register(registry);
        this.notificationsFailedCounter = Counter.builder("notifications.failed.total")
                .description("Total failed notification processing attempts")
                .register(registry);
    }

    @KafkaListener(topics = "orders.created")
    @Transactional
    public void onOrderCreated(String event) {
        JsonNode root = parseEvent(event);
        String eventId = getRequiredText(root, "id");

        // Idempotency check
        if (notificationRepository.existsByEventId(eventId)) {
            log.info("Duplicate event ignored: eventId={}", eventId);
            return;
        }

        JsonNode data = root.path("data");
        var notification = new Notification();
        notification.setEventId(eventId);
        notification.setOrderId(UUID.fromString(getRequiredText(data, "orderId")));
        notification.setUserId(getRequiredText(data, "userId"));
        notification.setMessage("Order %s processed with risk score %d".formatted(
                getRequiredText(data, "orderId"), data.path("riskScore").asInt()));
        notification.setChannel("EMAIL");

        try {
            notificationRepository.save(notification);
            notificationsSentCounter.increment();
            log.info("Notification sent: orderId={} eventId={}", notification.getOrderId(), eventId);
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate — unique constraint caught it; this is expected
            log.info("Duplicate event caught by constraint: eventId={}", eventId);
        }
    }

    private JsonNode parseEvent(String event) {
        try {
            return objectMapper.readTree(event);
        } catch (Exception e) {
            notificationsFailedCounter.increment();
            throw new IllegalArgumentException("Failed to parse event JSON", e);
        }
    }

    private String getRequiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            notificationsFailedCounter.increment();
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value.asText();
    }
}
