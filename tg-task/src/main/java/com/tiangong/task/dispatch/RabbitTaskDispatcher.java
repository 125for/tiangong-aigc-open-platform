package com.tiangong.task.dispatch;

import com.tiangong.task.service.WorkerExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * docker profile：RabbitMQ 派发（多消费者横向扩容）。
 * local profile 下完全不创建 Rabbit 相关 Bean，笔记本零依赖可跑。
 */
@Slf4j
@Component
@Profile("docker")
@RequiredArgsConstructor
public class RabbitTaskDispatcher implements TaskDispatcher {

    public static final String QUEUE = "tg.task.dispatch";

    private final RabbitTemplate rabbitTemplate;
    private final WorkerExecutor workerExecutor;

    @Override
    public void dispatch(String taskNo) {
        rabbitTemplate.convertAndSend("", QUEUE, taskNo);
    }

    @RabbitListener(queues = QUEUE)
    public void onMessage(String taskNo) {
        try {
            workerExecutor.execute(taskNo);
        } catch (Exception e) {
            log.error("[rabbit] execute failed taskNo={}", taskNo, e);
        }
    }

    @Configuration
    @Profile("docker")
    static class RabbitConfig {
        @Bean
        Queue taskDispatchQueue() {
            return new Queue(QUEUE, true);
        }
    }
}
