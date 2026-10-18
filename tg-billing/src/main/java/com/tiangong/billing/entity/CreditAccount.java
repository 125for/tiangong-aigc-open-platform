package com.tiangong.billing.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 积分账户：balance 可用余额，held 预扣冻结。
 * 不变式 balance>=0、held>=0，并发由 version 乐观锁保证（应用层重试）。
 */
@Data
@Entity
@NoArgsConstructor
@Table(name = "credit_accounts")
public class CreditAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private Integer balance;

    private Integer held;

    @Version
    private Long version;

    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (this.balance == null) {
            this.balance = 0;
        }
        if (this.held == null) {
            this.held = 0;
        }
        if (this.version == null) {
            this.version = 0L;
        }
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
