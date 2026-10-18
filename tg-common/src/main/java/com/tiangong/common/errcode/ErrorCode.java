package com.tiangong.common.errcode;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 全局错误码体系：首字母对应域（A 账号/通用参数、B 计费、C 任务、D 平台）。
 * 面向客户端稳定，禁止删除或改语义，只能新增。
 */
@Getter
public enum ErrorCode {

    OK(0, HttpStatus.OK, "OK"),

    // A: 通用参数 / 认证
    BAD_ARGUMENT(0xA0101, HttpStatus.BAD_REQUEST, "参数校验失败"),
    UNAUTHORIZED(0xA0111, HttpStatus.UNAUTHORIZED, "未认证或令牌无效"),
    REFRESH_TOKEN_INVALID(0xA0112, HttpStatus.UNAUTHORIZED, "刷新令牌无效或已撤销"),
    FORBIDDEN(0xA0121, HttpStatus.FORBIDDEN, "无权访问该资源"),
    CONFLICT(0xA0201, HttpStatus.CONFLICT, "资源冲突"),
    IDEMPOTENT_IN_FLIGHT(0xA0302, HttpStatus.CONFLICT, "相同幂等键的请求正在处理中，请勿并发重试"),
    IDEMPOTENT_KEY_MISMATCH(0xA0303, HttpStatus.BAD_REQUEST, "幂等键已存在但请求体不一致"),
    RATE_LIMITED(0xA0301, HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试"),
    NOT_FOUND(0xA0404, HttpStatus.NOT_FOUND, "资源不存在"),
    USER_NOT_FOUND(0xA0401, HttpStatus.NOT_FOUND, "用户不存在或已停用"),
    LOGIN_FAILED(0xA0113, HttpStatus.UNAUTHORIZED, "邮箱或密码错误"),

    // B: 计费
    PLAN_NOT_FOUND(0xB0101, HttpStatus.NOT_FOUND, "商品不存在"),
    ORDER_STATUS_INVALID(0xB0201, HttpStatus.CONFLICT, "订单状态不允许该操作"),
    PAY_SIGN_INVALID(0xB0202, HttpStatus.BAD_REQUEST, "支付回调验签失败"),
    PAY_AMOUNT_MISMATCH(0xB0203, HttpStatus.BAD_REQUEST, "回调金额与订单不符"),
    INSUFFICIENT_CREDITS(0xB0301, HttpStatus.CONFLICT, "积分不足"),
    OPTIMISTIC_LOCK_CONFLICT(0xB0302, HttpStatus.CONFLICT, "并发冲突，请重试"),

    // C: 任务
    TASK_NOT_FOUND(0xC0101, HttpStatus.NOT_FOUND, "任务不存在"),
    TASK_STATUS_INVALID(0xC0201, HttpStatus.CONFLICT, "任务状态不允许该操作"),
    TASK_PARAM_INVALID(0xC0202, HttpStatus.UNPROCESSABLE_ENTITY, "任务参数非法"),

    // D: 平台
    DOWNSTREAM_UNAVAILABLE(0xD0101, HttpStatus.SERVICE_UNAVAILABLE, "下游服务不可用，请稍后重试"),
    INTERNAL_ERROR(0xD9999, HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
