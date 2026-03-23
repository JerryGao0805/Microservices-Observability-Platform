package com.yanggao.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final RiskClient riskClient;
    private final OrderEventPublisher eventPublisher;
    private final Counter ordersCreatedCounter;
    private final Timer orderProcessingTimer;

    public OrderService(OrderRepository orderRepository, RiskClient riskClient,
                        OrderEventPublisher eventPublisher, MeterRegistry registry) {
        this.orderRepository = orderRepository;
        this.riskClient = riskClient;
        this.eventPublisher = eventPublisher;
        this.ordersCreatedCounter = Counter.builder("orders.created.total")
                .description("Total number of orders created")
                .register(registry);
        this.orderProcessingTimer = Timer.builder("orders.processing.duration")
                .description("Time to process an order")
                .register(registry);
    }

    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        return orderProcessingTimer.record(() -> {
            var order = new Order();
            order.setUserId(request.userId());
            order.setAmount(request.amount());
            order.setCurrency(request.currency());

            var riskResponse = riskClient.evaluate(request.amount(), request.currency());
            order.setRiskScore(riskResponse.riskScore());
            order.setStatus(riskResponse.riskScore() >= 90 ? "REJECTED" : "APPROVED");

            var saved = orderRepository.save(order);
            log.info("Order created: id={} status={} riskScore={}", saved.getId(), saved.getStatus(), saved.getRiskScore());

            eventPublisher.publishOrderCreated(saved);
            ordersCreatedCounter.increment();
            return saved;
        });
    }

    public Order getOrder(UUID id) {
        return orderRepository.findById(id).orElse(null);
    }

    public Page<OrderResponse> listOrders(Pageable pageable) {
        return orderRepository.findAll(pageable).map(OrderResponse::from);
    }
}
