package com.tiangong.task.service;

import com.tiangong.task.config.TaskProperties;
import com.tiangong.task.entity.TaskEntity;
import com.tiangong.task.repo.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 任务执行器：PENDING → RUNNING →（重试）→ SUCCEEDED / FAILED。
 * 与超时扫描器通过状态 CAS 互斥：晚到的一方迁移失败即退出，不重复动账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerExecutor {

    private final TaskRepository taskRepo;
    private final TaskProperties props;
    private final TaskFinisher taskFinisher;
    private final TaskStore taskStore;

    public void execute(String taskNo) {
        TaskEntity task = taskRepo.findByTaskNo(taskNo).orElse(null);
        if (task == null || !"PENDING".equals(task.getStatus())) {
            return;   // 已被取消/超时
        }
        if (!taskStore.transition(taskNo, "PENDING", "RUNNING", "开始执行")) {
            return;
        }
        int max = Math.max(1, task.getMaxAttempts() == null ? props.getMaxAttempts() : task.getMaxAttempts());
        for (int attempt = 1; attempt <= max; attempt++) {
            if (task.getTimeoutAt() != null && task.getTimeoutAt().isBefore(LocalDateTime.now())) {
                log.info("[worker] task {} exceeded ttl before attempt {}", taskNo, attempt);
                taskFinisher.timeout(reload(task));
                return;
            }
            boolean ok = props.simulateInference(taskNo);
            if (ok) {
                taskFinisher.succeed(reload(task), resultUrlOf(taskNo));
                return;
            }
            taskRepo.bumpAttempts(taskNo);
            log.info("[worker] task {} attempt {}/{} failed", taskNo, attempt, max);
            if (attempt < max) {
                try {
                    Thread.sleep(props.backoff(attempt).toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        taskFinisher.fail(reload(task), "ENGINE_FAILED",
                "推理失败（重试 " + max + " 次后放弃），已自动退款");
    }

    private TaskEntity reload(TaskEntity stale) {
        return taskRepo.findByTaskNo(stale.getTaskNo()).orElse(stale);
    }

    private String resultUrlOf(String taskNo) {
        return "https://cdn.tiangong.local/results/" + taskNo + ".png";
    }
}
