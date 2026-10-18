package com.tiangong.common.web;

import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import com.tiangong.common.util.JwtSupport;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 当前用户解析：优先取网关注入的 X-User-Id；缺失时兜底解析 Bearer JWT（直连调用/测试场景）。
 * 服务侧二次解析同时构成纵深防御：伪造 X-User-Id 但无有效 JWT 的直连请求会被拒绝。
 */
public final class CurrentUser {

    public static final String HEADER = "X-User-Id";

    private CurrentUser() {
    }

    public static long userId(HttpServletRequest request, String jwtSecret) {
        String uid = request.getHeader(HEADER);
        if (uid != null && !uid.isBlank()) {
            return Long.parseLong(uid);
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return JwtSupport.parseAccessToken(jwtSecret, auth.substring(7)).userId();
        }
        throw new BizException(ErrorCode.UNAUTHORIZED);
    }
}
