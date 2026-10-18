package com.tiangong.task.client;

import com.tiangong.common.web.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 下游 billing 客户端（Feign + Resilience4j 熔断，实例名 billing-client）。
 * 不设 fallback：4xx 业务错误（积分不足等）必须原样透传给调用方，
 * 熔断打开(CallNotPermittedException)/连接失败由 BillingGateway 统一映射。
 */
@FeignClient(name = "billing-client", url = "${tg.dep.billing-base-url:http://localhost:8082}")
public interface BillingClient {

    @PostMapping("/internal/v1/credits/hold")
    Result<Map<String, Object>> hold(@RequestBody HoldReq req);

    @PostMapping("/internal/v1/credits/settle")
    Result<Map<String, Object>> settle(@RequestBody BizReq req);

    @PostMapping("/internal/v1/credits/refund")
    Result<Map<String, Object>> refund(@RequestBody BizReq req);

    @GetMapping("/internal/v1/credits/holds/{bizNo}")
    Result<Map<String, Object>> holdStatus(@PathVariable("bizNo") String bizNo);

    record HoldReq(Long userId, String bizNo, Integer credits) {
    }

    record BizReq(String bizNo) {
    }
}
