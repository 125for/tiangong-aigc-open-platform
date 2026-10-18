package com.tiangong.billing.client;

import com.tiangong.billing.dto.Dtos;
import com.tiangong.common.web.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 下游 account 客户端：Feign + Resilience4j 熔断（实例名 account-client）。
 * 不可用时走 AccountClientFallback → 快速失败 D0101（下单是低频操作，宁可拒绝不可降级伪造）。
 */
@FeignClient(name = "account-client", url = "${tg.dep.account-base-url:http://localhost:8081}",
        fallbackFactory = AccountClientFallbackFactory.class)
public interface AccountClient {

    @GetMapping("/internal/v1/users/{id}")
    Result<Dtos.InternalUserVO> getUser(@PathVariable("id") long id);
}
