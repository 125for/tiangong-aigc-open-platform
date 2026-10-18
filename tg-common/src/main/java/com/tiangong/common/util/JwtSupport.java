package com.tiangong.common.util;

import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发/解析（HS256）。account 签发，gateway 与各服务用同一密钥解析。
 * 生产建议升级 RS256 + JWKS：网关无需共享私钥，支持密钥轮换。
 */
public final class JwtSupport {

    public record AccessClaims(long userId, String email, String nickname) {
    }

    private JwtSupport() {
    }

    public static String issueAccessToken(String secret, long userId, String email,
                                          String nickname, long ttlSeconds) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("uid", userId)
                .claim("email", email)
                .claim("nickname", nickname)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    /** 过期/签名错误统一抛 UNAUTHORIZED（不区分原因，避免向攻击者泄露信息）。 */
    public static AccessClaims parseAccessToken(String secret, String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return new AccessClaims(claims.get("uid", Long.class),
                    claims.get("email", String.class), claims.get("nickname", String.class));
        } catch (ExpiredJwtException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌已过期");
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌无效");
        }
    }
}
