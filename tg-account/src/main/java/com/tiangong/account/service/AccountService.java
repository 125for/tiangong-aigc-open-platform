package com.tiangong.account.service;

import com.tiangong.account.dto.Dtos;
import com.tiangong.account.dto.Dtos.InternalAppVO;
import com.tiangong.account.dto.Dtos.InternalUserVO;
import com.tiangong.account.entity.App;
import com.tiangong.account.entity.RefreshToken;
import com.tiangong.account.entity.User;
import com.tiangong.account.repo.AppRepository;
import com.tiangong.account.repo.RefreshTokenRepository;
import com.tiangong.account.repo.UserRepository;
import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import com.tiangong.common.util.CryptoUtil;
import com.tiangong.common.util.IdGen;
import com.tiangong.common.util.JwtSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AppRepository appRepository;
    private final RefreshTokenRevoker refreshTokenRevoker;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${tg.jwt.secret}")
    private String jwtSecret;

    @Value("${tg.jwt.access-ttl-seconds:1800}")
    private long accessTtlSeconds;

    @Value("${tg.jwt.refresh-ttl-days:7}")
    private long refreshTtlDays;

    @Value("${tg.aes-key}")
    private String aesKey;

    // ---------------------------------------------------------------- 用户与令牌

    @Transactional
    public User register(String email, String password, String nickname) {
        if (userRepository.existsByEmail(email)) {
            throw new BizException(ErrorCode.CONFLICT, "邮箱已注册");
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname);
        try {
            return userRepository.save(user);   // 并发注册兜底：uk_users_email 唯一约束
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BizException(ErrorCode.CONFLICT, "邮箱已注册");
        }
    }

    @Transactional
    public Dtos.LoginResp login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BizException(ErrorCode.LOGIN_FAILED));
        if (!"ACTIVE".equals(user.getStatus()) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException(ErrorCode.LOGIN_FAILED);   // 统一文案，不区分“用户不存在/密码错”
        }
        return issuePair(user);
    }

    /** 轮换：旧 RT 一次性作废；检测到已用 RT 重放 → 撤销整个家族。 */
    @Transactional
    public Dtos.LoginResp refresh(String rawRefreshToken) {
        String hash = CryptoUtil.sha256Hex(rawRefreshToken);
        RefreshToken rt = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BizException(ErrorCode.REFRESH_TOKEN_INVALID));
        switch (rt.getStatus() == null ? "" : rt.getStatus()) {
            case "USED" -> {   // 重放攻击信号
                int revoked = refreshTokenRevoker.revokeFamily(rt.getFamilyId());   // REQUIRES_NEW，先落库
                log.warn("[security] refresh token replay detected, family={} revoked={}", rt.getFamilyId(), revoked);
                throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID, "检测到令牌重放，请重新登录");
            }
            case "REVOKED" -> throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
            default -> { }
        }
        if (rt.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID, "刷新令牌已过期");
        }
        rt.setStatus("USED");
        refreshTokenRepository.save(rt);
        User user = userRepository.findById(rt.getUserId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return issuePair(user, rt.getFamilyId());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(CryptoUtil.sha256Hex(rawRefreshToken))
                .ifPresent(rt -> {
                    rt.setStatus("REVOKED");
                    refreshTokenRepository.save(rt);
                });
    }

    private Dtos.LoginResp issuePair(User user) {
        return issuePair(user, null);
    }

    private Dtos.LoginResp issuePair(User user, Long familyId) {
        String access = JwtSupport.issueAccessToken(jwtSecret, user.getId(), user.getEmail(),
                user.getNickname(), accessTtlSeconds);
        String rawRefresh = IdGen.refreshToken();
        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(CryptoUtil.sha256Hex(rawRefresh));
        rt.setStatus("ACTIVE");
        rt.setExpiresAt(LocalDateTime.now().plusDays(refreshTtlDays));
        refreshTokenRepository.save(rt);
        if (familyId == null) {
            rt.setFamilyId(rt.getId());     // 新家族：以首条 id 为族号
            refreshTokenRepository.save(rt);
        } else {
            rt.setFamilyId(familyId);
        }
        return new Dtos.LoginResp(user.getId(), user.getEmail(), user.getNickname(),
                new Dtos.TokenPair(access, rawRefresh, accessTtlSeconds));
    }

    public User getUser(long userId) {
        return userRepository.findById(userId)
                .filter(u -> "ACTIVE".equals(u.getStatus()))
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 开发者应用

    @Transactional
    public Dtos.AppVO createApp(long userId, String name, String callbackUrl) {
        App app = new App();
        app.setUserId(userId);
        app.setAppKey(IdGen.appKey());
        String secret = IdGen.appSecret();
        app.setSecretCipher(CryptoUtil.aesGcmEncrypt(aesKey, secret));
        app.setName(name);
        app.setCallbackUrl(callbackUrl);
        appRepository.save(app);
        return toVO(app, secret);
    }

    @Transactional
    public Dtos.AppVO resetSecret(long userId, String appKey) {
        App app = ownedApp(userId, appKey);
        String secret = IdGen.appSecret();
        app.setSecretCipher(CryptoUtil.aesGcmEncrypt(aesKey, secret));
        appRepository.save(app);
        return toVO(app, secret);
    }

    @Transactional
    public Dtos.AppVO patchApp(long userId, String appKey, String name, String callbackUrl, Integer rateLimit) {
        App app = ownedApp(userId, appKey);
        if (name != null) {
            app.setName(name);
        }
        if (callbackUrl != null) {
            app.setCallbackUrl(callbackUrl);
        }
        if (rateLimit != null && (rateLimit < 1 || rateLimit > 100000)) {
            throw new BizException(ErrorCode.BAD_ARGUMENT, "限流配额需在 1~100000 之间");
        }
        if (rateLimit != null) {
            app.setRateLimitPerMin(rateLimit);
        }
        return toVO(appRepository.save(app), null);
    }

    public List<Dtos.AppVO> listApps(long userId) {
        return appRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(a -> toVO(a, null))
                .toList();
    }

    public InternalAppVO internalGetApp(String appKey) {
        App app = appRepository.findByAppKey(appKey)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "应用不存在"));
        return new InternalAppVO(app.getAppKey(), app.getUserId(),
                CryptoUtil.aesGcmDecrypt(aesKey, app.getSecretCipher()), app.getStatus(),
                app.getRateLimitPerMin(), app.getCallbackUrl());
    }

    public InternalUserVO internalGetUser(long userId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));
        return new InternalUserVO(u.getId(), u.getEmail(), u.getNickname(), u.getStatus());
    }

    /** 控制台用户直接提交任务时，默认取其第一个 ACTIVE 应用。 */
    public String defaultAppKeyOf(long userId) {
        return appRepository.findFirstByUserIdAndStatusOrderByCreatedAtAsc(userId, "ACTIVE")
                .map(App::getAppKey)
                .orElseThrow(() -> new BizException(ErrorCode.CONFLICT, "请先创建应用再提交任务"));
    }

    private App ownedApp(long userId, String appKey) {
        App app = appRepository.findByAppKey(appKey)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "应用不存在"));
        if (!app.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return app;
    }

    private Dtos.AppVO toVO(App app, String secretOnce) {
        return new Dtos.AppVO(app.getAppKey(), app.getName(), app.getCallbackUrl(),
                app.getStatus(), app.getRateLimitPerMin(), secretOnce);
    }
}
