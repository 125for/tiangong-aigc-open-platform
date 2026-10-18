package com.tiangong.gateway.filter;

import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import com.tiangong.common.util.JwtSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * JWT 认证过滤器。
 * 安全要点：
 *  1) 无条件剥离外部传入的 X-User-Id / X-App-User-Id（防直连伪造透传）；
 *  2) 白名单路径放行（剥离仍执行）；
 *  3) Bearer 验签通过后注入 X-User-Id；
 *  4) 带 X-App-Key 的请求交给 AppKeyAuthGlobalFilter 验签。
 */
@Slf4j
@Component
@Order(-100)
public class JwtAuthGlobalFilter implements WebFilter {

    private static final List<String> STRIP_HEADERS = List.of(
            "X-User-Id", "X-App-User-Id", "X-App-Callback-Url");

    private static final List<String> WHITELIST_PREFIX = List.of(
            "/api/v1/auth/", "/api/v1/plans", "/api/v1/payments/callback", "/api/v1/webhooks/sink");

    @Value("${tg.jwt.secret}")
    private String jwtSecret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        ServerWebExchange safe = exchange.mutate()
                .request(b -> STRIP_HEADERS.forEach(h -> b.headers(hh -> hh.remove(h))))
                .build();

        if (WHITELIST_PREFIX.stream().anyMatch(path::startsWith)
                || path.startsWith("/actuator")) {
            return chain.filter(safe);
        }
        String appKey = request.getHeaders().getFirst("X-App-Key");
        if (appKey != null && !appKey.isBlank()) {
            return chain.filter(safe);   // AppKey 验签流程接管
        }
        String auth = request.getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return reject(safe, new BizException(ErrorCode.UNAUTHORIZED));
        }
        try {
            long uid = JwtSupport.parseAccessToken(jwtSecret, auth.substring(7)).userId();
            ServerWebExchange authed = safe.mutate()
                    .request(b -> b.headers(h -> h.set("X-User-Id", String.valueOf(uid))))
                    .build();
            return chain.filter(authed);
        } catch (BizException e) {
            return reject(safe, e);
        }
    }

    static Mono<Void> reject(ServerWebExchange exchange, BizException e) {
        var response = exchange.getResponse();
        response.setRawStatusCode(e.getErrorCode().getHttpStatus().value());
        response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + e.getErrorCode().getCode()
                + ",\"message\":\"" + e.getMessage() + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory()
                .wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
}
