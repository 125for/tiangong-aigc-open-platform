package com.tiangong.common.web;

import com.tiangong.common.errcode.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 服务间内部接口（/internal/**）鉴权：校验 X-Internal-Token。
 * 生产环境应叠加网络隔离（K8s NetworkPolicy / mTLS），令牌仅是应用层防线。
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final String expectedToken;

    public InternalTokenFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/internal/") && !expectedToken.equals(request.getHeader(HEADER))) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":" + ErrorCode.UNAUTHORIZED.getCode()
                    + ",\"message\":\"internal token invalid\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
