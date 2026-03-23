package com.yanggao.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderEventConsumer {

    @KafkaListener(topics = "orders.created")
    public void onOrderCreated(String event) {
        log.info("Received OrderCreatedEvent: {}", event);
    }
}
