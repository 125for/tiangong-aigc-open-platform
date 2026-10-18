package com.tiangong.common.web;

import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常 → 统一响应体。
 * 原则：业务异常按语义透出；未知异常只暴露通用信息（不泄漏堆栈），完整堆栈进日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e, HttpServletRequest req) {
        return build(e.getErrorCode(), e.getMessage(), req);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class})
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(ErrorCode.BAD_ARGUMENT, detail, req);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Result<Void>> handleIllegal(RuntimeException e, HttpServletRequest req) {
        return build(ErrorCode.BAD_ARGUMENT, e.getMessage(), req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoResourceFoundException e, HttpServletRequest req) {
        return build(ErrorCode.NOT_FOUND, "资源不存在: " + req.getRequestURI(), req);
    }

    /** 唯一约束冲突：并发下的幂等/重复注册竞态兜底（第一道防线是业务层显式判断）。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleIntegrity(DataIntegrityViolationException e, HttpServletRequest req) {
        log.warn("[data-integrity] uri={} msg={}", req.getRequestURI(), e.getMostSpecificCause().getMessage());
        return build(ErrorCode.CONFLICT, "资源已存在或并发冲突", req);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Result<Void>> handleOptimistic(OptimisticLockingFailureException e, HttpServletRequest req) {
        return build(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, null, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknown(Exception e, HttpServletRequest req) {
        log.error("[internal-error] uri={}", req.getRequestURI(), e);
        return build(ErrorCode.INTERNAL_ERROR, null, req);
    }

    private ResponseEntity<Result<Void>> build(ErrorCode ec, String message, HttpServletRequest req) {
        Result<Void> body = Result.fail(ec.getCode(), message != null ? message : ec.getDefaultMessage());
        body.fillTraceId(MDC.get("traceId"));
        return ResponseEntity.status(ec.getHttpStatus()).body(body);
    }
}
