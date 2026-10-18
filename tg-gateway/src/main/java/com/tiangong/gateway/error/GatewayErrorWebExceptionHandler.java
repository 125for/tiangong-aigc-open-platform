package com.tiangong.gateway.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.gateway.filter.TraceIdGlobalFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关统一错误出口：下游不可达/超时 → 503 D0101；其余未知 → 500 D9999。
 * 以统一 Result JSON 返回并带 traceId，避免裸 500 堆栈泄漏。
 */
@Slf4j
@Component
@Order(-2)
@RequiredArgsConstructor
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper om;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus s = rse.getStatusCode() instanceof HttpStatus hs
                    ? hs : HttpStatus.resolve(rse.getStatusCode().value());
            if (s != null && (s.is5xxServerError() || s == HttpStatus.BAD_GATEWAY)) {
                code = ErrorCode.DOWNSTREAM_UNAVAILABLE;
                status = HttpStatus.SERVICE_UNAVAILABLE;
            } else if (s == HttpStatus.NOT_FOUND) {
                code = ErrorCode.NOT_FOUND;
                status = HttpStatus.NOT_FOUND;
            } else if (s != null) {
                status = s;
                code = ErrorCode.BAD_ARGUMENT;
            }
        }
        log.warn("[gateway-error] path={} status={} cause={}",
                exchange.getRequest().getURI().getPath(), status, String.valueOf(ex));

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(
                org.springframework.http.MediaType.APPLICATION_JSON);
        String traceId = String.valueOf(exchange.getAttribute(TraceIdGlobalFilter.ATTR));
        String body;
        try {
            body = om.writeValueAsString(new com.tiangong.common.web.Result<>(
                    code.getCode(), code.getDefaultMessage(), null, traceId));
        } catch (Exception e) {
            body = "{\"code\":142289,\"message\":\"服务器内部错误\"}";
        }
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
