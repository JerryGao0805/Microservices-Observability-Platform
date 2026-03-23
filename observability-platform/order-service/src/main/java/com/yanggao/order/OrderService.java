package com.yanggao.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final RiskClient riskClient;
    private final OrderEventPublisher eventPublisher;

    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        var order = new Order();
        order.setUserId(request.userId());
        order.setAmount(request.amount());
        order.setCurrency(request.currency());

        var riskResponse = riskClient.evaluate(request.amount(), request.currency());
        order.setRiskScore(riskResponse.riskScore());
        order.setStatus(riskResponse.riskScore() >= 90 ? "REJECTED" : "APPROVED");

        order = orderRepository.save(order);
        log.info("Order created: id={} status={} riskScore={}", order.getId(), order.getStatus(), order.getRiskScore());

        eventPublisher.publishOrderCreated(order);
        return order;
    }

    public Order getOrder(UUID id) {
        return orderRepository.findById(id).orElse(null);
    }
}
