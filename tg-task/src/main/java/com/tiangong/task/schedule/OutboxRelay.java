package com.tiangong.task.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiangong.task.client.BillingGateway;
import com.tiangong.task.dispatch.TaskDispatcher;
import com.tiangong.task.repo.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Outbox 中继：本地消息表 → 真实投递（进程内 / MQ）与资金补偿重试。
 * 投递失败指数退避，10 次后转 DEAD（人工出口）；定时任务永不向外抛异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final int BATCH = 10;
    private static final int MAX_ATTEMPTS = 10;

    private final OutboxRepository outboxRepo;
    private final TaskDispatcher dispatcher;
    private final BillingGateway billingGateway;
    private final ObjectMapper om;

    @Scheduled(fixedDelayString = "${tg.task.outbox-interval-ms:2000}", initialDelay = 1000)
    public void relay() {
        try {
            relayOnce();
        } catch (Exception e) {
            log.error("[outbox-relay] sweep failed", e);
        }
    }

    @Transactional
    void relayOnce() {
        var due = outboxRepo.pickDue(LocalDateTime.now(), PageRequest.of(0, BATCH));
        for (var m : due) {
            try {
                String taskNo = om.readValue(m.getPayloadJson(), String.class);
                switch (m.getMsgType()) {
                    case "TASK_DISPATCH" -> dispatcher.dispatch(taskNo);
                    case TaskFinisherMsg.MSG_SETTLE -> billingGateway.settle(taskNo);
                    case TaskFinisherMsg.MSG_REFUND -> billingGateway.refund(taskNo);
                    default -> log.warn("[outbox-relay] unknown msgType {}", m.getMsgType());
                }
                outboxRepo.advance(m.getId(), "SENT", LocalDateTime.now());
            } catch (Exception e) {
                int nextAttempts = m.getAttempts() + 1;
                String nextStatus = nextAttempts >= MAX_ATTEMPTS ? "DEAD" : "PENDING";
                long backoffSec = 1L << Math.min(nextAttempts, 5);
                outboxRepo.advance(m.getId(), nextStatus,
                        LocalDateTime.now().plusSeconds(backoffSec));
                log.warn("[outbox-relay] msg {} attempt {} → {} ({})",
                        m.getId(), nextAttempts, nextStatus, String.valueOf(e));
            }
        }
    }

    /** 消息类型常量与 TaskFinisher 保持一致（避免循环依赖，此处只引用常量）。 */
    public static final class TaskFinisherMsg {
        public static final String MSG_SETTLE = "COMPENSATION_SETTLE";
        public static final String MSG_REFUND = "COMPENSATION_REFUND";

        private TaskFinisherMsg() {
        }
    }
}
