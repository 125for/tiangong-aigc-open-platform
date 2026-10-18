package com.tiangong.task.repo;

import com.tiangong.task.entity.WebhookDelivery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    @Query("SELECT d FROM WebhookDelivery d WHERE d.status = 'PENDING' AND d.nextAttemptAt <= :now "
            + "ORDER BY d.id ASC")
    List<WebhookDelivery> pickDue(@Param("now") LocalDateTime now, Pageable pageable);

    /** 自足事务：中继自调用场景下依旧可用。 */
    @Transactional
    @Modifying
    @Query("UPDATE WebhookDelivery d SET d.status = :status, d.attempts = d.attempts + 1, "
            + "d.lastError = :err, d.responseCode = :code, d.nextAttemptAt = :next "
            + "WHERE d.id = :id AND d.status = 'PENDING'")
    int advance(@Param("id") Long id, @Param("status") String status, @Param("err") String err,
                @Param("code") Integer code, @Param("next") LocalDateTime next);

    List<WebhookDelivery> findTop20ByTaskNoOrderByCreatedAtDesc(String taskNo);
}
