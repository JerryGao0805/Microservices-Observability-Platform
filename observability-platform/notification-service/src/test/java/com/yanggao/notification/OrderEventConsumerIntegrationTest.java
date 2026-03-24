package com.yanggao.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class OrderEventConsumerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    NotificationRepository notificationRepository;

    @BeforeEach
    void cleanUp() {
        notificationRepository.deleteAll();
    }

    @Test
    void consumeEvent_savesNotification() {
        String eventId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();
        String event = buildEvent(eventId, orderId, "test-user", 500, "USD");

        kafkaTemplate.send("orders.created", orderId, event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(notificationRepository.existsByEventId(eventId)).isTrue();
        });

        var notification = notificationRepository.findAll().stream()
                .filter(n -> eventId.equals(n.getEventId()))
                .findFirst().orElseThrow();
        assertThat(notification.getOrderId()).hasToString(orderId);
        assertThat(notification.getUserId()).isEqualTo("test-user");
        assertThat(notification.getChannel()).isEqualTo("EMAIL");
    }

    @Test
    void consumeDuplicateEvent_onlyOneNotification() {
        String eventId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();
        String event = buildEvent(eventId, orderId, "dup-user", 100, "EUR");

        kafkaTemplate.send("orders.created", orderId, event);

        // Wait for first to be processed
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationRepository.existsByEventId(eventId)).isTrue());

        // Send duplicate
        kafkaTemplate.send("orders.created", orderId, event);

        // Wait and verify only one notification exists
        await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            long count = notificationRepository.findAll().stream()
                    .filter(n -> eventId.equals(n.getEventId()))
                    .count();
            assertThat(count).isEqualTo(1);
        });
    }

    @Test
    void consumeEvent_withMissingFields_doesNotSave() {
        String eventId = UUID.randomUUID().toString();
        String invalidEvent = """
                {
                  "specversion":"1.0",
                  "id":"%s",
                  "source":"order-service",
                  "type":"com.yanggao.order.created",
                  "data":{}
                }
                """.formatted(eventId);

        kafkaTemplate.send("orders.created", "key", invalidEvent);

        // The consumer should fail to process this — no notification saved
        await().during(3, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationRepository.existsByEventId(eventId)).isFalse());
    }

    private String buildEvent(String eventId, String orderId, String userId, int amount, String currency) {
        return """
                {
                  "specversion":"1.0",
                  "id":"%s",
                  "source":"order-service",
                  "type":"com.yanggao.order.created",
                  "time":"2026-01-01T00:00:00Z",
                  "data":{
                    "orderId":"%s",
                    "userId":"%s",
                    "amount":%d,
                    "currency":"%s",
                    "riskScore":50
                  }
                }
                """.formatted(eventId, orderId, userId, amount, currency);
    }
}
