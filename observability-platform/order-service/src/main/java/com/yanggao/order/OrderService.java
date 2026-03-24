package com.yanggao.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final RiskClient riskClient;
    private final ApplicationEventPublisher eventPublisher;
    private final Counter ordersCreatedCounter;
    private final Counter ordersRejectedCounter;
    private final Timer orderProcessingTimer;

    private static final int MAX_PAGE_SIZE = 100;

    public OrderService(OrderRepository orderRepository, RiskClient riskClient,
                        ApplicationEventPublisher eventPublisher, MeterRegistry registry) {
        this.orderRepository = orderRepository;
        this.riskClient = riskClient;
        this.eventPublisher = eventPublisher;
        this.ordersCreatedCounter = Counter.builder("orders.created")
                .description("Total number of orders created")
                .register(registry);
        this.ordersRejectedCounter = Counter.builder("orders.rejected")
                .description("Total number of orders rejected")
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

            if (riskResponse.riskScore() >= 90) {
                order.setStatus(OrderStatus.REJECTED);
                ordersRejectedCounter.increment();
            } else {
                order.setStatus(OrderStatus.APPROVED);
                ordersCreatedCounter.increment();
            }

            var saved = orderRepository.save(order);
            log.info("Order created: id={} status={} riskScore={}", saved.getId(), saved.getStatus(), saved.getRiskScore());

            // Published after commit via @TransactionalEventListener
            eventPublisher.publishEvent(new OrderCreatedEvent(saved));
            return saved;
        });
    }

    public Optional<Order> getOrder(UUID id) {
        return orderRepository.findById(id);
    }

    public Page<OrderResponse> listOrders(Pageable pageable) {
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        var capped = PageRequest.of(pageable.getPageNumber(), size, pageable.getSort());
        return orderRepository.findAll(capped).map(OrderResponse::from);
    }
}
