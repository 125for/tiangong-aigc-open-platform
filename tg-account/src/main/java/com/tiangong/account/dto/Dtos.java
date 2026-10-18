package com.tiangong.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Dtos {

    private Dtos() {
    }

    public record RegisterReq(
            @Email @NotBlank @Size(max = 128) String email,
            @NotBlank @Size(min = 8, max = 64) String password,
            @NotBlank @Size(max = 32) String nickname) {
    }

    public record LoginReq(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record RefreshReq(@NotBlank String refreshToken) {
    }

    public record LogoutReq(@NotBlank String refreshToken) {
    }

    public record TokenPair(String accessToken, String refreshToken, long accessExpiresInSeconds) {
    }

    public record LoginResp(Long userId, String email, String nickname, TokenPair tokens) {
    }

    public record UserVO(Long userId, String email, String nickname, String status) {
    }

    public record AppCreateReq(@NotBlank @Size(max = 64) String name, @Size(max = 512) String callbackUrl) {
    }

    public record AppPatchReq(@Size(max = 64) String name, @Size(max = 512) String callbackUrl,
                              Integer rateLimitPerMin) {
    }

    /** AppSecret 仅创建/重置响应中出现一次。 */
    public record AppVO(String appKey, String name, String callbackUrl, String status,
                        Integer rateLimitPerMin, String appSecret) {
    }

    /** 网关验签用（internal），含解密后的 secret。 */
    public record InternalAppVO(String appKey, Long userId, String secret, String status,
                                Integer rateLimitPerMin, String callbackUrl) {
    }

    public record InternalUserVO(Long userId, String email, String nickname, String status) {
    }
}
