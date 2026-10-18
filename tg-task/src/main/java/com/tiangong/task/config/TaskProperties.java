package com.tiangong.task.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** 任务域可调参数（演示/压测/故障注入全部经由配置，不改代码）。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "tg.task")
public class TaskProperties {

    /** 任务类型 → 积分定价 */
    private Map<String, Integer> pricing = Map.of(
            "TEXT_TO_IMAGE", 10,
            "TEXT_TO_VIDEO", 50,
            "TTS", 5);

    /** 任务总时长预算（PENDING+RUNNING），到点由扫描器终结 */
    private int ttlSeconds = 120;

    /** 执行失败重试次数上限 */
    private int maxAttempts = 3;

    private long sweepIntervalMs = 5000;

    private long outboxIntervalMs = 2000;

    private Engine engine = new Engine();

    private Webhook webhook = new Webhook();

    @Data
    public static class Engine {
        /** 模拟推理耗时（毫秒） */
        private long latencyMs = 2500;
        /** 模拟推理失败率 0~1（故障注入） */
        private double failRate = 0.1;
    }

    @Data
    public static class Webhook {
        private int maxAttempts = 5;
    }

    public int priceOf(String type) {
        Integer p = pricing.get(type);
        if (p == null) {
            throw new IllegalArgumentException("不支持的任务类型: " + type);
        }
        return p;
    }

    public Duration backoff(int attempt) {
        return Duration.ofSeconds((long) (1L << Math.min(attempt, 4)));   // 1s,2s,4s,8s,16s
    }

    /** 模拟推理：可配置耗时与失败率（±30% 抖动）。 */
    public boolean simulateInference(String taskNo) {
        long jitter = ThreadLocalRandom.current().nextLong(-30, 31);
        long sleep = Math.max(50, engine.latencyMs + engine.latencyMs * jitter / 100);
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        return ThreadLocalRandom.current().nextDouble() >= engine.failRate;
    }
}
