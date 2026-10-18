package com.tiangong.billing.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 支付单：payment_no 唯一约束是回调幂等的锚点。 */
@Data
@Entity
@NoArgsConstructor
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String paymentNo;

    private String orderNo;

    private String channel;

    private Integer amountCents;

    private String status;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = "RECEIVED";
        }
        if (this.channel == null) {
            this.channel = "MOCK";
        }
    }
}
