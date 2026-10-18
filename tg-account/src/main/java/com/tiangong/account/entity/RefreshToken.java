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

/**
 * RefreshToken 只存 SHA-256 摘要。
 * 轮换策略：使用一次即置 USED 并签发新 token（同 family）；
 * 已 USED 的 token 再次出现 = 令牌被盗/重放 → 撤销整个 family 强制重新登录。
 */
@Data
@Entity
@NoArgsConstructor
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    /** SHA-256(raw token) hex */
    private String tokenHash;

    private Long familyId;

    /** ACTIVE / USED / REVOKED */
    private String status;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
