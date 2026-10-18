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

/** API 幂等键：唯一约束抢占 PROCESSING 位，DONE 后回放响应快照（同键同果）。 */
@Data
@Entity
@NoArgsConstructor
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String idemKey;

    private String endpoint;

    private String requestHash;

    /** PROCESSING / DONE */
    private String status;

    private String responseJson;

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = "PROCESSING";
        }
    }
}
