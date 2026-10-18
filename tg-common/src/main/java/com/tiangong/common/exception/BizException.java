package com.tiangong.common.exception;

import com.tiangong.common.errcode.ErrorCode;
import lombok.Getter;

/** 业务异常：携带稳定错误码，由 GlobalExceptionHandler 统一转成响应体。 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }
}
