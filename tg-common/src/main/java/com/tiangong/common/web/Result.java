package com.tiangong.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体。code=0 成功；非 0 见 {@link com.tiangong.common.errcode.ErrorCode}。
 * HTTP 状态码供基础设施（网关/重试器）判断，业务码供客户端细分处理。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "OK", data, null);
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, null);
    }

    public void fillTraceId(String traceId) {
        this.traceId = traceId;
    }
}
