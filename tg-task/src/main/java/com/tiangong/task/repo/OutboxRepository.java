package com.tiangong.task.repo;

import com.tiangong.task.entity.OutboxMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    /** 中继捞取：PENDING 且到期（status 条件保证多实例安全）。 */
    @Query("SELECT m FROM OutboxMessage m WHERE m.status = 'PENDING' AND m.nextAttemptAt <= :now "
            + "ORDER BY m.id ASC")
    List<OutboxMessage> pickDue(@Param("now") LocalDateTime now, Pageable pageable);

    /** 自足事务：中继自调用场景下依旧可用（不依赖调用方事务）。 */
    @Transactional
    @Modifying
    @Query("UPDATE OutboxMessage m SET m.status = :status, m.attempts = m.attempts + 1, "
            + "m.nextAttemptAt = :next WHERE m.id = :id AND m.status = 'PENDING'")
    int advance(@Param("id") Long id, @Param("status") String status,
                @Param("next") LocalDateTime next);
}
