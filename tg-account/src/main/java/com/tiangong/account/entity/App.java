package com.tiangong.account.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 开发者应用：AppKey 公开、AppSecret AES-GCM 加密落库（明文仅创建/重置时返回一次）。 */
@Data
@Entity
@NoArgsConstructor
@Table(name = "apps")
public class App {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private String appKey;

    private String secretCipher;

    private String name;

    private String callbackUrl;

    /** ACTIVE / DISABLED */
    private String status;

    private Integer rateLimitPerMin;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = "ACTIVE";
        }
        if (this.rateLimitPerMin == null) {
            this.rateLimitPerMin = 120;
        }
    }
}
