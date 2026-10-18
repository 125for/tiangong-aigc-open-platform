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

/**
 * 账务流水（append-only）：余额只是快照，流水是事实，任何不平可重放对账。
 * UNIQUE(biz_type, biz_no, change_type) 保证同一业务的每种迁移全局仅发生一次。
 */
@Data
@Entity
@NoArgsConstructor
@Table(name = "credit_ledgers")
public class CreditLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    /** GRANT / HOLD / SETTLE / REFUND / EXPIRE */
    private String changeType;

    /** 对 balance 的有符号影响（SETTLE 为 0，仅释放 held） */
    private Integer amount;

    private Integer balanceAfter;

    /** ORDER / TASK / ADMIN */
    private String bizType;

    private String bizNo;

    private String remark;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
