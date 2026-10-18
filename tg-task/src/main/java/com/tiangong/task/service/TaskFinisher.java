package com.tiangong.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiangong.task.client.BillingGateway;
import com.tiangong.task.entity.TaskEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 终态收口：谁赢得状态 CAS，谁负责资金终结（结算或退款）。
 * 资金调用失败 → 写 Outbox 补偿消息（COMPENSATION_SETTLE/REFUND），中继异步重试 —— 资金必达，不降级不丢失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskFinisher {

    private final TaskStore taskStore;
    private final BillingGateway billingGateway;
    private final ObjectMapper om;

    public static final String MSG_SETTLE = "COMPENSATION_SETTLE";
    public static final String MSG_REFUND = "COMPENSATION_REFUND";

    public boolean succeed(TaskEntity task, String resultUrl) {
        if (!taskStore.transitionSucceed(task.getTaskNo(), resultUrl)) {
            return false;   // 已被超时扫描器等竞争者终结
        }
        task.setResultUrl(resultUrl);
        notify(task, "SUCCEEDED");
        settleOrCompensate(task);
        return true;
    }

    public boolean fail(TaskEntity task, String errorCode, String errorMsg) {
        if (!taskStore.transitionFail(task.getTaskNo(), "RUNNING", errorCode, errorMsg)) {
            return false;
        }
        task.setErrorCode(errorCode);
        notify(task, "FAILED");
        refundOrCompensate(task);
        return true;
    }

    public boolean timeout(TaskEntity task) {
        String from = task.getStatus();
        if (!taskStore.transition(task.getTaskNo(), from, "TIMEOUT",
                "超过 TTL " + " 自动终结并退款")) {
            return false;
        }
        notify(task, "TIMEOUT");
        refundOrCompensate(task);
        return true;
    }

    public boolean cancel(TaskEntity task) {
        if (!taskStore.transition(task.getTaskNo(), "PENDING", "CANCELED", "用户取消")) {
            return false;
        }
        notify(task, "CANCELED");
        refundOrCompensate(task);
        return true;
    }

    private void notify(TaskEntity task, String status) {
        try {
            taskStore.enqueueWebhook(task, status);
        } catch (Exception e) {
            log.error("[webhook] enqueue failed taskNo={}", task.getTaskNo(), e);
        }
    }

    private void settleOrCompensate(TaskEntity task) {
        try {
            billingGateway.settle(task.getTaskNo());
        } catch (Exception e) {
            log.warn("[settle] direct settle failed, enqueue compensation: {}", String.valueOf(e));
            compensate(MSG_SETTLE, task);
        }
    }

    private void refundOrCompensate(TaskEntity task) {
        try {
            billingGateway.refund(task.getTaskNo());
        } catch (Exception e) {
            log.warn("[refund] direct refund failed, enqueue compensation: {}", String.valueOf(e));
            compensate(MSG_REFUND, task);
        }
    }

    private void compensate(String msgType, TaskEntity task) {
        try {
            taskStore.enqueueOutbox(msgType, task.getId(), om.writeValueAsString(task.getTaskNo()));
        } catch (Exception e) {
            log.error("[outbox] enqueue compensation failed taskNo={} type={}",
                    task.getTaskNo(), msgType, e);
        }
    }

    /** 落库失败后的兜底退款（尽力而为，失败依赖人工/巡检）。 */
    public void refundQuietly(String taskNo) {
        try {
            billingGateway.refund(taskNo);
        } catch (Exception e) {
            log.error("[refund-quietly] failed taskNo={} — 需人工介入对账", taskNo, e);
        }
    }
}
