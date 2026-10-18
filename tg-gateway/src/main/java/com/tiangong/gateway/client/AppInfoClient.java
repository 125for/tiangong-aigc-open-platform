package com.tiangong.gateway.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import com.tiangong.common.web.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * 网关 → account 内部接口：取应用信息（含解密后的 secret，用于验签）。
 * Caffeine 缓存 60s：验签高频路径不打 account；secret 重置最长 60s 生效（可接受）。
 */
@Slf4j
@Component
public class AppInfoClient {

    public record AppInfo(String appKey, long userId, String secret, String status,
                          int rateLimitPerMin, String callbackUrl) {
    }

    private final WebClient webClient;

    private final Cache<String, Mono<AppInfo>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();

    public AppInfoClient(WebClient.Builder builder,
                         @Value("${tg.dep.account-base-url}") String accountBaseUrl,
                         @Value("${tg.internal-token}") String internalToken) {
        this.webClient = builder.baseUrl(accountBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    public Mono<AppInfo> get(String appKey) {
        Mono<AppInfo> cached = cache.get(appKey, k -> fetch(k).cache());
        assert cached != null;
        return cached
                .doOnError(e -> cache.invalidate(appKey))   // 失败不缓存
                .timeout(Duration.ofSeconds(2))
                .onErrorMap(e -> {
                    log.warn("[app-info] fetch failed appKey={}: {}", appKey, String.valueOf(e));
                    return new BizException(ErrorCode.DOWNSTREAM_UNAVAILABLE, "应用信息获取失败");
                });
    }

    private Mono<AppInfo> fetch(String appKey) {
        return webClient.get().uri("/internal/v1/apps/{k}", appKey)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Result<Map<String, Object>>>() {
                })
                .flatMap(r -> {
                    Map<String, Object> data = r == null ? null : r.getData();
                    if (r == null || r.getCode() != 0 || data == null) {
                        return Mono.error(new BizException(ErrorCode.NOT_FOUND, "应用不存在"));
                    }
                    return Mono.just(new AppInfo(
                            appKey,
                            ((Number) data.getOrDefault("userId", 0)).longValue(),
                            (String) data.get("secret"),
                            (String) data.getOrDefault("status", ""),
                            data.get("rateLimitPerMin") == null ? 120
                                    : ((Number) data.get("rateLimitPerMin")).intValue(),
                            (String) data.get("callbackUrl")));
                });
    }
}
