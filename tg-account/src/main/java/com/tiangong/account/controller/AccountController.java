package com.tiangong.account.controller;

import com.tiangong.account.dto.Dtos.AppCreateReq;
import com.tiangong.account.dto.Dtos.AppPatchReq;
import com.tiangong.account.dto.Dtos.AppVO;
import com.tiangong.account.dto.Dtos.UserVO;
import com.tiangong.account.entity.User;
import com.tiangong.account.service.AccountService;
import com.tiangong.common.web.CurrentUser;
import com.tiangong.common.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @Value("${tg.jwt.secret}")
    private String jwtSecret;

    @GetMapping("/users/me")
    public Result<UserVO> me(HttpServletRequest request) {
        User u = accountService.getUser(CurrentUser.userId(request, jwtSecret));
        return Result.ok(new UserVO(u.getId(), u.getEmail(), u.getNickname(), u.getStatus()));
    }

    @GetMapping("/apps")
    public Result<List<AppVO>> listApps(HttpServletRequest request) {
        return Result.ok(accountService.listApps(CurrentUser.userId(request, jwtSecret)));
    }

    @PostMapping("/apps")
    public Result<AppVO> createApp(@Valid @RequestBody AppCreateReq req, HttpServletRequest request) {
        return Result.ok(accountService.createApp(CurrentUser.userId(request, jwtSecret),
                req.name(), req.callbackUrl()));
    }

    @PostMapping("/apps/{appKey}/reset-secret")
    public Result<AppVO> resetSecret(@PathVariable String appKey, HttpServletRequest request) {
        return Result.ok(accountService.resetSecret(CurrentUser.userId(request, jwtSecret), appKey));
    }

    @PatchMapping("/apps/{appKey}")
    public Result<AppVO> patchApp(@PathVariable String appKey,
                                  @Valid @RequestBody AppPatchReq req,
                                  HttpServletRequest request) {
        return Result.ok(accountService.patchApp(CurrentUser.userId(request, jwtSecret), appKey,
                req.name(), req.callbackUrl(), req.rateLimitPerMin()));
    }
}
