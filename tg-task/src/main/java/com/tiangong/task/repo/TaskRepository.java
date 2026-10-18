package com.tiangong.task.repo;

import com.tiangong.task.entity.TaskEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<TaskEntity, Long> {

    Optional<TaskEntity> findByTaskNo(String taskNo);

    Page<TaskEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** 状态机 CAS：仅当当前状态等于 from 才迁移，返回影响行数判定是否竞争成功。 */
    @Modifying
    @Query("UPDATE TaskEntity t SET t.status = :to, t.updatedAt = :now "
            + "WHERE t.taskNo = :taskNo AND t.status = :from")
    int casStatus(@Param("taskNo") String taskNo, @Param("from") String from,
                  @Param("to") String to, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE TaskEntity t SET t.status = :to, t.errorCode = :errorCode, t.errorMsg = :errorMsg, "
            + "t.updatedAt = :now WHERE t.taskNo = :taskNo AND t.status = :from")
    int casFail(@Param("taskNo") String taskNo, @Param("from") String from, @Param("to") String to,
                @Param("errorCode") String errorCode, @Param("errorMsg") String errorMsg,
                @Param("now") LocalDateTime now);

    /** 执行重试计数（每次推理失败 +1，终态时 attempts == 实际尝试次数）。 */
    @Transactional
    @Modifying
    @Query("UPDATE TaskEntity t SET t.attempts = t.attempts + 1 WHERE t.taskNo = :taskNo AND t.status = 'RUNNING'")
    int bumpAttempts(@Param("taskNo") String taskNo);

    @Modifying
    @Query("UPDATE TaskEntity t SET t.status = :to, t.resultUrl = :resultUrl, t.updatedAt = :now "
            + "WHERE t.taskNo = :taskNo AND t.status = :from")
    int casSucceed(@Param("taskNo") String taskNo, @Param("from") String from, @Param("to") String to,
                   @Param("resultUrl") String resultUrl, @Param("now") LocalDateTime now);

    /** 超时扫描：PENDING/RUNNING 且超过 TTL 的任务。 */
    @Query("SELECT t FROM TaskEntity t WHERE t.status IN ('PENDING','RUNNING') AND t.timeoutAt < :now")
    List<TaskEntity> findTimeoutCandidates(@Param("now") LocalDateTime now);
}
