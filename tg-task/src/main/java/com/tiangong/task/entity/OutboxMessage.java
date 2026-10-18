package com.tiangong.task.entity;

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
 * 本地消息表（Outbox）：业务变更与消息写入同一本地事务，中继轮询投递。
 * msg_type：TASK_DISPATCH（派发执行）/ COMPENSATION_SETTLE / COMPENSATION_REFUND（资金补偿）。
 */
@Data
@Entity
@NoArgsConstructor
@Table(name = "outbox_messages")
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String aggregateType;

    private Long aggregateId;

    private String msgType;

    private String payloadJson;

    /** PENDING / SENT / DEAD */
    private String status;

    private Integer attempts;

    private LocalDateTime nextAttemptAt;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = "PENDING";
        }
        if (this.attempts == null) {
            this.attempts = 0;
        }
        if (this.nextAttemptAt == null) {
            this.nextAttemptAt = this.createdAt;
        }
    }
}
