package com.tiangong.billing.client;

import com.tiangong.billing.dto.Dtos;
import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import com.tiangong.common.web.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class AccountClientFallbackFactory implements FallbackFactory<AccountClient> {

    private static final Logger log = LoggerFactory.getLogger(AccountClientFallbackFactory.class);

    @Override
    public AccountClient create(Throwable cause) {
        return new AccountClient() {
            @Override
            public Result<Dtos.InternalUserVO> getUser(long id) {
                log.warn("[circuit] account-client fallback: {}", String.valueOf(cause));
                throw new BizException(ErrorCode.DOWNSTREAM_UNAVAILABLE, "账号服务不可用，请稍后重试");
            }
        };
    }
}
