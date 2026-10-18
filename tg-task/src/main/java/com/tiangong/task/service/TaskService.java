package com.tiangong.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import com.tiangong.common.util.CryptoUtil;
import com.tiangong.common.util.IdGen;
import com.tiangong.task.client.BillingGateway;
import com.tiangong.task.config.TaskProperties;
import com.tiangong.task.dto.Dtos;
import com.tiangong.task.entity.OutboxMessage;
import com.tiangong.task.entity.TaskEntity;
import com.tiangong.task.entity.TaskEvent;
import com.tiangong.task.repo.TaskEventRepository;
import com.tiangong.task.repo.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务提交（幂等 + 预扣 + Outbox）、查询与取消。
 * 提交顺序：校验 → 幂等位 → 预扣积分（失败无任何持久化副作用）→ 本地事务(任务+派发消息)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    public static final String SUBMIT_ENDPOINT = "task-submit";

    private final TaskRepository taskRepo;
    private final TaskEventRepository eventRepo;
    private final TaskStore taskStore;
    private final TaskFinisher taskFinisher;
    private final BillingGateway billingGateway;
    private final TaskProperties props;
    private final IdempotencyService idempotency;
    private final ObjectMapper om;

    public Dtos.TaskSubmitVO submit(long userId, String appKey, Dtos.TaskSubmitReq req,
                                    String idemKeyHeader) {
        final int credits;
        try {
            credits = props.priceOf(req.type());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.TASK_PARAM_INVALID, e.getMessage());
        }
        String requestHash = CryptoUtil.sha256Hex(appKey + "|" + req.type() + "|"
                + req.prompt() + "|" + (req.paramsJson() == null ? "" : req.paramsJson()));

        if (idemKeyHeader != null && !idemKeyHeader.isBlank()) {
            var begin = idempotency.begin(idemKeyHeader, SUBMIT_ENDPOINT, requestHash);
            if (begin instanceof IdempotencyService.BeginResult.HitDone hit) {
                return fromJson(hit.responseJson());   // 同键回放：同请求同响应
            }
            if (begin instanceof IdempotencyService.BeginResult.InFlight) {
                throw new BizException(ErrorCode.IDEMPOTENT_IN_FLIGHT);
            }
            try {
                Dtos.TaskSubmitVO vo = doSubmit(userId, appKey, req, credits);
                idempotency.complete(idemKeyHeader, SUBMIT_ENDPOINT, toJson(vo));
                return vo;
            } catch (Exception e) {
                idempotency.abort(idemKeyHeader, SUBMIT_ENDPOINT);
                throw e;
            }
        }
        return doSubmit(userId, appKey, req, credits);
    }

    private Dtos.TaskSubmitVO doSubmit(long userId, String appKey, Dtos.TaskSubmitReq req, int credits) {
        String taskNo = IdGen.taskNo();
        billingGateway.hold(userId, taskNo, credits);   // 失败(积分不足/熔断) → 直接拒绝，无副作用

        TaskEntity task = new TaskEntity();
        task.setTaskNo(taskNo);
        task.setUserId(userId);
        task.setAppKey(appKey);
        task.setType(req.type());
        task.setPrompt(req.prompt());
        task.setParamsJson(req.paramsJson());
        task.setCostCredits(credits);
        task.setMaxAttempts(props.getMaxAttempts());
        task.setCallbackUrl(req.callbackUrl());
        task.setTimeoutAt(LocalDateTime.now().plusSeconds(props.getTtlSeconds()));

        OutboxMessage dispatch = new OutboxMessage();
        dispatch.setAggregateType("TASK");
        dispatch.setMsgType("TASK_DISPATCH");
        dispatch.setAggregateId(0L);
        dispatch.setPayloadJson(toJson(taskNo));   // 统一 JSON 字符串，中继侧反序列化

        try {
            taskStore.persistNew(task, dispatch);
        } catch (Exception e) {
            // 极端情况：预扣成功但本地落库失败 → 立即补偿退款，保证账实一致
            log.error("[submit] persist failed after hold, refunding {}", taskNo, e);
            taskFinisher.refundQuietly(taskNo);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "任务落库失败，已自动退款");
        }
        log.info("[submit] task={} userId={} appKey={} callback={} credits={}",
                taskNo, userId, appKey, task.getCallbackUrl(), credits);
        return new Dtos.TaskSubmitVO(taskNo, task.getStatus(), credits, task.getTimeoutAt());
    }

    public Dtos.TaskVO get(long userId, String taskNo) {
        return toVO(ownedTask(userId, taskNo));
    }

    public Dtos.TaskPageVO list(long userId, String status, int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        var result = taskRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        List<Dtos.TaskVO> items = result.getContent().stream()
                .filter(t -> status == null || status.isBlank() || status.equals(t.getStatus()))
                .map(this::toVO)
                .toList();
        return new Dtos.TaskPageVO(items, pageable.getPageNumber(), pageable.getPageSize(),
                result.getTotalElements());
    }

    public List<Dtos.TaskEventVO> events(long userId, String taskNo) {
        TaskEntity task = ownedTask(userId, taskNo);
        return eventRepo.findByTaskIdOrderByCreatedAtAscIdAsc(task.getId()).stream()
                .map(e -> new Dtos.TaskEventVO(e.getFromStatus(), e.getToStatus(), e.getDetail(),
                        e.getCreatedAt()))
                .toList();
    }

    public Dtos.TaskVO cancel(long userId, String taskNo) {
        TaskEntity task = ownedTask(userId, taskNo);
        boolean won = taskFinisher.cancel(task);
        if (!won) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "仅 PENDING 状态的任务可取消");
        }
        return toVO(taskRepo.findByTaskNo(taskNo).orElse(task));
    }

    private TaskEntity ownedTask(long userId, String taskNo) {
        TaskEntity task = taskRepo.findByTaskNo(taskNo)
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
        if (task.getUserId() != userId) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return task;
    }

    private Dtos.TaskVO toVO(TaskEntity t) {
        return new Dtos.TaskVO(t.getTaskNo(), t.getType(), t.getPrompt(), t.getStatus(),
                t.getCostCredits(), t.getAttempts(), t.getResultUrl(), t.getErrorCode(),
                t.getErrorMsg(), t.getTimeoutAt(), t.getCreatedAt(), t.getUpdatedAt());
    }

    private String toJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Dtos.TaskSubmitVO fromJson(String json) {
        try {
            return om.readValue(json, Dtos.TaskSubmitVO.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
