package com.yanggao.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Setter
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Setter
    @Column(nullable = false)
    private BigDecimal amount;

    @Setter
    @Column(nullable = false, length = 3)
    private String currency;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Setter
    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // For test use only
    void setId(UUID id) {
        this.id = id;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if (this.status == null) {
            this.status = OrderStatus.PENDING;
        }
    }
}
