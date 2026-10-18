package com.tiangong.task.entity;

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
 * AIGC 任务。状态机：
 *   PENDING → RUNNING → SUCCEEDED / FAILED
 *   PENDING → CANCELED
 *   PENDING/RUNNING → TIMEOUT（超时治理）
 * 所有迁移通过条件 UPDATE（WHERE status=from）实现 CAS，天然并发安全；
 * attempts 为执行重试计数（指数退避）。
 */
@Data
@Entity
@NoArgsConstructor
@Table(name = "tasks")
public class TaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String taskNo;

    private Long userId;

    private String appKey;

    private String type;

    private String prompt;

    private String paramsJson;

    private String status;

    private Integer costCredits;

    private Integer attempts;

    private Integer maxAttempts;

    private LocalDateTime timeoutAt;

    private String callbackUrl;

    private String resultUrl;

    private String errorCode;

    private String errorMsg;

    @Version
    private Long version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
        if (this.status == null) {
            this.status = "PENDING";
        }
        if (this.attempts == null) {
            this.attempts = 0;
        }
        if (this.maxAttempts == null) {
            this.maxAttempts = 3;
        }
        if (this.version == null) {
            this.version = 0L;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
