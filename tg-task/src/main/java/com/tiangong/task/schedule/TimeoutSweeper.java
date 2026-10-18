package com.tiangong.task.schedule;

import com.tiangong.task.entity.TaskEntity;
import com.tiangong.task.repo.TaskRepository;
import com.tiangong.task.service.TaskFinisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 超时治理：PENDING/RUNNING 超过 TTL 的任务 → TIMEOUT + 自动退款。
 * 走 (status, timeout_at) 索引；与 Worker 通过状态 CAS 互斥，晚到者不动账。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutSweeper {

    private final TaskRepository taskRepo;
    private final TaskFinisher taskFinisher;

    @Scheduled(fixedDelayString = "${tg.task.sweep-interval-ms:5000}", initialDelay = 3000)
    public void sweep() {
        try {
            var candidates = taskRepo.findTimeoutCandidates(LocalDateTime.now());
            for (TaskEntity task : candidates) {
                boolean won = taskFinisher.timeout(task);
                if (won) {
                    log.warn("[timeout-sweeper] task {} TIMEOUT, refund dispatched", task.getTaskNo());
                }
            }
        } catch (Exception e) {
            log.error("[timeout-sweeper] sweep failed", e);
        }
    }
}
