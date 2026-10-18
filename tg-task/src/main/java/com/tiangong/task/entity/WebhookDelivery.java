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

/** 任务终态 Webhook 投递记录：指数退避重试，超限转 FAILED（人工出口）。 */
@Data
@Entity
@NoArgsConstructor
@Table(name = "webhook_deliveries")
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String taskNo;

    private String url;

    private String payloadJson;

    /** PENDING / SUCCESS / FAILED */
    private String status;

    private Integer attempts;

    private Integer maxAttempts;

    private LocalDateTime nextAttemptAt;

    private String lastError;

    private Integer responseCode;

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
        if (this.maxAttempts == null) {
            this.maxAttempts = 5;
        }
        if (this.nextAttemptAt == null) {
            this.nextAttemptAt = this.createdAt;
        }
    }
}
