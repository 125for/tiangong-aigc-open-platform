package com.tiangong.gateway.filter;

import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import com.tiangong.gateway.client.AppInfoClient;
import com.tiangong.gateway.security.NonceStore;
import com.tiangong.gateway.security.SignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * 开放 API AppKey 验签（对标主流开放平台）：
 *  - 头部：X-App-Key / X-Timestamp / X-Nonce / X-Signature；
 *  - 签名覆盖 method + path + body 摘要 → 拦截中途篡改；
 *  - 时间窗 ±300s + nonce 去重 → 拦截重放；
 *  - 验证通过注入 X-App-User-Id（下游任务服务据其记账与鉴权）。
 * 请求体在此缓存后以 decorator 继续向下游转发（body 只读一次的 WebFlux 语义）。
 */
@Slf4j
@Component
@Order(-90)
@RequiredArgsConstructor
public class AppKeyAuthGlobalFilter implements WebFilter {

    private final AppInfoClient appInfoClient;
    private final NonceStore nonceStore;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String appKey = request.getHeaders().getFirst("X-App-Key");
        if (appKey == null || appKey.isBlank()) {
            return chain.filter(exchange);   // 非开放 API 调用（JWT 已在上一过滤器处理）
        }
        String timestamp = request.getHeaders().getFirst("X-Timestamp");
        String nonce = request.getHeaders().getFirst("X-Nonce");
        String signature = request.getHeaders().getFirst("X-Signature");
        if (timestamp == null || nonce == null || signature == null) {
            return JwtAuthGlobalFilter.reject(exchange,
                    new BizException(ErrorCode.UNAUTHORIZED, "签名头缺失"));
        }
        return DataBufferUtils.join(request.getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return verifyAndForward(exchange, chain, appKey, timestamp, nonce, signature,
                            bytes);
                });
    }

    private Mono<Void> verifyAndForward(ServerWebExchange exchange, WebFilterChain chain,
                                        String appKey, String timestamp, String nonce,
                                        String signature, byte[] bodyBytes) {
        return appInfoClient.get(appKey)
                .flatMap(app -> {
                    if (!"ACTIVE".equals(app.status())) {
                        return JwtAuthGlobalFilter.reject(exchange,
                                new BizException(ErrorCode.FORBIDDEN, "应用已停用"));
                    }
                    boolean ok = SignatureVerifier.verify(app.secret(), appKey, timestamp, nonce,
                            exchange.getRequest().getMethod().name(),
                            exchange.getRequest().getURI().getPath(), bodyBytes, signature);
                    if (!ok) {
                        log.warn("[app-auth] signature invalid appKey={} path={}", appKey,
                                exchange.getRequest().getURI().getPath());
                        return JwtAuthGlobalFilter.reject(exchange,
                                new BizException(ErrorCode.UNAUTHORIZED, "签名无效或已过期"));
                    }
                    return nonceStore.tryAcquire(nonce, SignatureVerifier.WINDOW_MILLIS * 2)
                            .flatMap(fresh -> {
                                if (!fresh) {
                                    log.warn("[app-auth] nonce replay appKey={} nonce={}", appKey, nonce);
                                    return JwtAuthGlobalFilter.reject(exchange,
                                            new BizException(ErrorCode.UNAUTHORIZED, "重复的 nonce"));
                                }
                                ServerHttpRequest withHeaders = exchange.getRequest().mutate()
                                        .headers(h -> {
                                            h.set("X-App-Key", appKey);
                                            h.set("X-App-User-Id", String.valueOf(app.userId()));
                                            if (app.callbackUrl() != null && !app.callbackUrl().isBlank()) {
                                                // URL 编码防 CRLF 注入；任务服务解码后使用
                                                h.set("X-App-Callback-Url",
                                                        java.net.URLEncoder.encode(app.callbackUrl(),
                                                                java.nio.charset.StandardCharsets.UTF_8));
                                            }
                                        })
                                        .build();
                                ServerHttpRequest decorated = new ServerHttpRequestDecorator(withHeaders) {
                                    @Override
                                    public Flux<DataBuffer> getBody() {
                                        return Flux.defer(() -> Flux.just(
                                                exchange.getResponse().bufferFactory()
                                                        .wrap(bodyBytes)));
                                    }
                                };
                                return chain.filter(exchange.mutate().request(decorated).build());
                            });
                })
                .onErrorResume(BizException.class,
                        e -> JwtAuthGlobalFilter.reject(exchange, e));
    }
}
