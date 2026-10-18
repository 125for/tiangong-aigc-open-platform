package com.tiangong.task.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import com.tiangong.common.web.Result;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * billing 调用网关：把 Feign/熔断异常映射回业务语义。
 *  - 409 B0301 → 积分不足；409 B0302 → 并发冲突（可重试）；其余按下游 message 透传
 *  - 熔断打开/连接失败/超时 → D0101 快速失败
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingGateway {

    private final BillingClient billingClient;
    private final ObjectMapper om;

    public Map<String, Object> hold(long userId, String bizNo, int credits) {
        return call(() -> billingClient.hold(new BillingClient.HoldReq(userId, bizNo, credits)));
    }

    public Map<String, Object> settle(String bizNo) {
        return call(() -> billingClient.settle(new BillingClient.BizReq(bizNo)));
    }

    public Map<String, Object> refund(String bizNo) {
        return call(() -> billingClient.refund(new BillingClient.BizReq(bizNo)));
    }

    public Map<String, Object> holdStatus(String bizNo) {
        return call(() -> billingClient.holdStatus(bizNo));
    }

    private Map<String, Object> call(java.util.function.Supplier<Result<Map<String, Object>>> call) {
        try {
            Result<Map<String, Object>> r = call.get();
            if (r == null || r.getCode() != 0) {
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        r == null ? "billing 空响应" : r.getMessage());
            }
            return r.getData();
        } catch (CallNotPermittedException e) {
            log.warn("[circuit] billing circuit OPEN, fast-fail");
            throw new BizException(ErrorCode.DOWNSTREAM_UNAVAILABLE, "计费服务熔断保护中，请稍后重试");
        } catch (FeignException e) {
            throw mapBizError(e);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[billing-call] unexpected: {}", String.valueOf(e));
            throw new BizException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
    }

    private BizException mapBizError(FeignException e) {
        try {
            JsonNode node = om.readTree(e.contentUTF8());
            int code = node.path("code").asInt();
            String message = node.path("message").asText("计费服务返回错误");
            if (code == ErrorCode.INSUFFICIENT_CREDITS.getCode()) {
                return new BizException(ErrorCode.INSUFFICIENT_CREDITS, message);
            }
            if (code == ErrorCode.OPTIMISTIC_LOCK_CONFLICT.getCode()) {
                return new BizException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, message);
            }
            if (e.status() >= 500) {
                return new BizException(ErrorCode.DOWNSTREAM_UNAVAILABLE, message);
            }
            return new BizException(ErrorCode.CONFLICT, message);
        } catch (Exception parseFail) {
            if (e.status() >= 500) {
                return new BizException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
            }
            return new BizException(ErrorCode.CONFLICT, "计费服务返回错误");
        }
    }
}
