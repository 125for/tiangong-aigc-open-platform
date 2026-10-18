package com.tiangong.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiangong.task.config.TaskProperties;
import com.tiangong.task.entity.OutboxMessage;
import com.tiangong.task.entity.TaskEntity;
import com.tiangong.task.entity.TaskEvent;
import com.tiangong.task.entity.WebhookDelivery;
import com.tiangong.task.repo.OutboxRepository;
import com.tiangong.task.repo.TaskEventRepository;
import com.tiangong.task.repo.TaskRepository;
import com.tiangong.task.repo.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 任务落库原语：每个方法一个短事务（状态 CAS + 审计事件 + Outbox 原子提交）。
 * 金钱操作（settle/refund）一律放在事务之外执行，避免跨服务 HTTP 调用拖长行锁持有时间。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskStore {

    private final TaskRepository taskRepo;
    private final TaskEventRepository eventRepo;
    private final OutboxRepository outboxRepo;
    private final WebhookDeliveryRepository webhookRepo;
    private final ObjectMapper om;

    @Transactional
    public void persistNew(TaskEntity task, OutboxMessage dispatch) {
        taskRepo.saveAndFlush(task);
        eventRepo.save(eventOf(task.getTaskNo(), null, "PENDING", "任务创建并预扣 " + task.getCostCredits() + " 积分"));
        outboxRepo.saveAndFlush(dispatch);
    }

    /** 通用状态迁移（CAS + 事件），竞争失败返回 false。 */
    @Transactional
    public boolean transition(String taskNo, String from, String to, String detail) {
        if (taskRepo.casStatus(taskNo, from, to, LocalDateTime.now()) == 0) {
            return false;
        }
        eventRepo.save(eventOf(taskNo, from, to, detail));
        return true;
    }

    @Transactional
    public boolean transitionSucceed(String taskNo, String resultUrl) {
        if (taskRepo.casSucceed(taskNo, "RUNNING", "SUCCEEDED", resultUrl, LocalDateTime.now()) == 0) {
            return false;
        }
        eventRepo.save(eventOf(taskNo, "RUNNING", "SUCCEEDED", "推理完成"));
        return true;
    }

    @Transactional
    public boolean transitionFail(String taskNo, String from, String errorCode, String errorMsg) {
        if (taskRepo.casFail(taskNo, from, "FAILED", errorCode, errorMsg, LocalDateTime.now()) == 0) {
            return false;
        }
        eventRepo.save(eventOf(taskNo, from, "FAILED", errorMsg));
        return true;
    }

    @Transactional
    public void enqueueOutbox(String msgType, long aggregateId, String payloadJson) {
        OutboxMessage m = new OutboxMessage();
        m.setAggregateType("TASK");
        m.setAggregateId(aggregateId);
        m.setMsgType(msgType);
        m.setPayloadJson(payloadJson);
        outboxRepo.saveAndFlush(m);
    }

    @Transactional
    public void enqueueWebhook(TaskEntity task, String status) {
        if (task.getCallbackUrl() == null || task.getCallbackUrl().isBlank()) {
            return;
        }
        WebhookDelivery d = new WebhookDelivery();
        d.setTaskNo(task.getTaskNo());
        d.setUrl(task.getCallbackUrl());
        d.setPayloadJson(toJson(new com.tiangong.task.dto.Dtos.WebhookPayload(
                "task.finished", task.getTaskNo(), status, task.getResultUrl(),
                task.getErrorCode(), LocalDateTime.now().toString())));
        webhookRepo.saveAndFlush(d);
    }

    private TaskEvent eventOf(String taskNo, String from, String to, String detail) {
        Long taskId = taskRepo.findByTaskNo(taskNo).map(TaskEntity::getId).orElse(null);
        TaskEvent e = new TaskEvent();
        e.setTaskId(taskId);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setDetail(detail);
        return e;
    }

    private String toJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
