package com.tiangong.gateway.filter;

import com.tiangong.gateway.client.AppInfoClient;
import com.tiangong.gateway.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 限流：AppKey 维度（应用配额，来自 account）优先，否则 IP 维度。
 * 超限 429 + Retry-After，统一错误体 A0301。
 */
@Slf4j
@Component
@Order(-200)
@RequiredArgsConstructor
public class RateLimitGlobalFilter implements WebFilter {

    private final RateLimiter rateLimiter;
    private final AppInfoClient appInfoClient;

    @Value("${tg.ratelimit.ip-per-min:120}")
    private int ipPerMin;

    @Value("${tg.ratelimit.enabled:true}")
    private boolean enabled;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        String appKey = exchange.getRequest().getHeaders().getFirst("X-App-Key");
        if (appKey != null && !appKey.isBlank()) {
            return appInfoClient.get(appKey)
                    .flatMap(info -> check(exchange, chain, "app:" + appKey, info.rateLimitPerMin()))
                    .onErrorResume(e -> check(exchange, chain, "ip:" + ip(exchange), ipPerMin));
        }
        return check(exchange, chain, "ip:" + ip(exchange), ipPerMin);
    }

    private Mono<Void> check(ServerWebExchange exchange, WebFilterChain chain,
                             String key, int limit) {
        return rateLimiter.tryAcquire(key, limit)
                .flatMap(allowed -> {
                    if (allowed) {
                        return chain.filter(exchange);
                    }
                    log.warn("[rate-limit] rejected key={} limit={}/min", key, limit);
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    exchange.getResponse().getHeaders().add("Retry-After", "1");
                    byte[] body = ("{\"code\":667649,\"message\":\"请求过于频繁，请稍后重试\"}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponse().getHeaders()
                            .setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                    return exchange.getResponse()
                            .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
                });
    }

    private String ip(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return exchange.getRequest().getRemoteAddress() == null ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }
}
