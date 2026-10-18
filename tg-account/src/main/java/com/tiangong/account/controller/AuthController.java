package com.tiangong.account.controller;

import com.tiangong.account.dto.Dtos.LoginReq;
import com.tiangong.account.dto.Dtos.LoginResp;
import com.tiangong.account.dto.Dtos.LogoutReq;
import com.tiangong.account.dto.Dtos.RefreshReq;
import com.tiangong.account.dto.Dtos.RegisterReq;
import com.tiangong.account.service.AccountService;
import com.tiangong.common.web.CurrentUser;
import com.tiangong.common.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AccountService accountService;

    @Value("${tg.jwt.secret}")
    private String jwtSecret;

    @PostMapping("/register")
    public Result<LoginResp> register(@Valid @RequestBody RegisterReq req) {
        accountService.register(req.email(), req.password(), req.nickname());
        return Result.ok(accountService.login(req.email(), req.password()));
    }

    @PostMapping("/login")
    public Result<LoginResp> login(@Valid @RequestBody LoginReq req) {
        return Result.ok(accountService.login(req.email(), req.password()));
    }

    @PostMapping("/refresh")
    public Result<LoginResp> refresh(@Valid @RequestBody RefreshReq req) {
        return Result.ok(accountService.refresh(req.refreshToken()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@Valid @RequestBody LogoutReq req, HttpServletRequest request) {
        CurrentUser.userId(request, jwtSecret);
        accountService.logout(req.refreshToken());
        return Result.ok();
    }
}
