package com.yanggao.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @MockBean
    OrderService orderService;

    @Test
    void createOrder_valid_returns201() throws Exception {
        var order = buildOrder("APPROVED", 50);
        when(orderService.createOrder(any())).thenReturn(order);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user1","amount":500,"currency":"USD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.riskScore").value(50));
    }

    @Test
    void createOrder_missingUserId_returns422() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":500,"currency":"USD"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    void createOrder_negativeAmount_returns422() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user1","amount":-10,"currency":"USD"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createOrder_invalidCurrency_returns422() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user1","amount":100,"currency":"TOOLONG"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getOrder_notFound_returns404() throws Exception {
        when(orderService.getOrder(any())).thenReturn(null);

        mockMvc.perform(get("/api/orders/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void createOrder_serviceUnavailable_returns503() throws Exception {
        when(orderService.createOrder(any())).thenThrow(new ServiceUnavailableException("Risk service unavailable"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user1","amount":500,"currency":"USD"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Risk service unavailable"));
    }

    private Order buildOrder(String status, int riskScore) {
        var order = new Order();
        order.setId(UUID.randomUUID());
        order.setUserId("user1");
        order.setAmount(new BigDecimal("500"));
        order.setCurrency("USD");
        order.setStatus(status);
        order.setRiskScore(riskScore);
        order.setCreatedAt(Instant.now());
        return order;
    }
}
