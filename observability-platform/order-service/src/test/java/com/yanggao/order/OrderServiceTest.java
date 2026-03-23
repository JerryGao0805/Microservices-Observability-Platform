package com.yanggao.order;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    OrderRepository orderRepository;
    @Mock
    RiskClient riskClient;
    @Mock
    OrderEventPublisher eventPublisher;

    OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, riskClient, eventPublisher, new SimpleMeterRegistry());
    }

    @Test
    void createOrder_approved() {
        when(riskClient.evaluate(any(), any()))
                .thenReturn(new RiskClient.RiskEvaluationResponse(50, "MEDIUM"));
        when(orderRepository.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(UUID.randomUUID());
            return o;
        });

        var result = orderService.createOrder(new CreateOrderRequest("user1", new BigDecimal("500"), "USD"));

        assertThat(result.getStatus()).isEqualTo("APPROVED");
        assertThat(result.getRiskScore()).isEqualTo(50);
        verify(eventPublisher).publishOrderCreated(any());
    }

    @Test
    void createOrder_rejected() {
        when(riskClient.evaluate(any(), any()))
                .thenReturn(new RiskClient.RiskEvaluationResponse(90, "HIGH"));
        when(orderRepository.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(UUID.randomUUID());
            return o;
        });

        var result = orderService.createOrder(new CreateOrderRequest("user1", new BigDecimal("2000"), "USD"));

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        assertThat(result.getRiskScore()).isEqualTo(90);
    }

    @Test
    void getOrder_found() {
        var order = new Order();
        order.setId(UUID.randomUUID());
        order.setUserId("user1");
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThat(orderService.getOrder(order.getId())).isEqualTo(order);
    }

    @Test
    void getOrder_notFound() {
        var id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(orderService.getOrder(id)).isNull();
    }
}
