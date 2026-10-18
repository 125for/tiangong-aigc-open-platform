package com.tiangong.task.controller;

import com.tiangong.common.web.CurrentUser;
import com.tiangong.common.web.Result;
import com.tiangong.task.dto.Dtos;
import com.tiangong.task.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务 API。认证双通道：
 *  - AppKey 签名（网关验签后注入 X-App-User-Id / X-App-Key）
 *  - 控制台 JWT（Bearer 解析；提交任务需在 body.appKey 指定应用）
 * 幂等：请求头 Idempotency-Key。
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @Value("${tg.jwt.secret}")
    private String jwtSecret;

    @PostMapping
    public Result<Dtos.TaskSubmitVO> submit(
            @Valid @RequestBody Dtos.TaskSubmitReq req,
            @RequestHeader(value = "X-App-Key", required = false) String headerAppKey,
            @RequestHeader(value = "X-App-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-App-Callback-Url", required = false) String headerCallback,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            HttpServletRequest request) {
        long userId = resolveUserId(headerUserId, request);
        String appKey = headerAppKey != null ? headerAppKey : req.appKey();
        if (appKey == null || appKey.isBlank()) {
            throw new com.tiangong.common.exception.BizException(
                    com.tiangong.common.errcode.ErrorCode.TASK_PARAM_INVALID, "缺少 appKey");
        }
        // 请求级回调优先；否则用网关注入的应用级回调（URL 解码）
        if ((req.callbackUrl() == null || req.callbackUrl().isBlank()) && headerCallback != null) {
            req = new Dtos.TaskSubmitReq(req.type(), req.prompt(), req.paramsJson(),
                    java.net.URLDecoder.decode(headerCallback, java.nio.charset.StandardCharsets.UTF_8),
                    req.appKey());
        }
        return Result.ok(taskService.submit(userId, appKey, req, idemKey));
    }

    @GetMapping("/{taskNo}")
    public Result<Dtos.TaskVO> get(@PathVariable String taskNo,
                                   @RequestHeader(value = "X-App-User-Id", required = false) String headerUserId,
                                   HttpServletRequest request) {
        return Result.ok(taskService.get(resolveUserId(headerUserId, request), taskNo));
    }

    @GetMapping
    public Result<Dtos.TaskPageVO> list(
            @RequestHeader(value = "X-App-User-Id", required = false) String headerUserId,
            HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(taskService.list(resolveUserId(headerUserId, request), status, page, size));
    }

    @PostMapping("/{taskNo}/cancel")
    public Result<Dtos.TaskVO> cancel(
            @PathVariable String taskNo,
            @RequestHeader(value = "X-App-User-Id", required = false) String headerUserId,
            HttpServletRequest request) {
        return Result.ok(taskService.cancel(resolveUserId(headerUserId, request), taskNo));
    }

    @GetMapping("/{taskNo}/events")
    public Result<?> events(
            @PathVariable String taskNo,
            @RequestHeader(value = "X-App-User-Id", required = false) String headerUserId,
            HttpServletRequest request) {
        return Result.ok(taskService.events(resolveUserId(headerUserId, request), taskNo));
    }

    private long resolveUserId(String headerUserId, HttpServletRequest request) {
        if (headerUserId != null && !headerUserId.isBlank()) {
            return Long.parseLong(headerUserId);   // 网关验签后注入（内网可信）
        }
        return CurrentUser.userId(request, jwtSecret);
    }
}
