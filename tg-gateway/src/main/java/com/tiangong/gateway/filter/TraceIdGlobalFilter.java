package com.tiangong.gateway.filter;

import com.tiangong.common.web.TraceIdFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** 入口生成/透传 X-Trace-Id，向下游与响应头同时输出（全链路可串联）。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class TraceIdGlobalFilter implements WebFilter {

    public static final String ATTR = "tg.traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TraceIdFilter.HEADER);
        if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        final String trace = traceId;
        exchange.getAttributes().put(ATTR, trace);
        exchange.getResponse().getHeaders().set(TraceIdFilter.HEADER, trace);
        ServerWebExchange mutated = exchange.mutate()
                .request(b -> b.headers(h -> h.set(TraceIdFilter.HEADER, trace)))
                .build();
        return chain.filter(mutated);
    }
}
