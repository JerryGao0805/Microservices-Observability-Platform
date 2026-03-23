package com.yanggao.order;

import java.math.BigDecimal;

public record CreateOrderRequest(String userId, BigDecimal amount, String currency) {
}
