package com.tiangong.task.dispatch;

import com.tiangong.task.service.WorkerExecutor;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * local profile：进程内派发（同一 TaskDispatcher 契约，docker profile 换 RabbitMQ 实现）。
 * 线程池=推理并发度；执行异常一律吞掉并记日志（任务由超时扫描器兜底）。
 */
@Slf4j
@Component
@Profile("!docker")
@RequiredArgsConstructor
public class InMemoryTaskDispatcher implements TaskDispatcher {

    private final WorkerExecutor workerExecutor;

    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "task-worker");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void dispatch(String taskNo) {
        pool.submit(() -> {
            try {
                workerExecutor.execute(taskNo);
            } catch (Exception e) {
                log.error("[dispatch] execute failed taskNo={}", taskNo, e);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }
}
