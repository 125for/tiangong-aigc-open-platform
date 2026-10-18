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

/** 测试用回调接收器（验收 Webhook 是否真实送达）。 */
@Data
@Entity
@NoArgsConstructor
@Table(name = "webhook_sink_records")
public class WebhookSinkRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String payloadJson;

    private LocalDateTime receivedAt;

    @PrePersist
    void onCreate() {
        this.receivedAt = LocalDateTime.now();
    }
}
