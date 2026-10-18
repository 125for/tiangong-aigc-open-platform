package com.tiangong.account.controller;

import com.tiangong.account.dto.Dtos.InternalAppVO;
import com.tiangong.account.dto.Dtos.InternalUserVO;
import com.tiangong.account.service.AccountService;
import com.tiangong.common.web.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 服务间内部接口：X-Internal-Token 鉴权（见 tg-common InternalTokenFilter）。 */
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalController {

    private final AccountService accountService;

    @GetMapping("/apps/{appKey}")
    public Result<InternalAppVO> getApp(@PathVariable String appKey) {
        return Result.ok(accountService.internalGetApp(appKey));
    }

    @GetMapping("/users/{id}")
    public Result<InternalUserVO> getUser(@PathVariable long id) {
        return Result.ok(accountService.internalGetUser(id));
    }

    @GetMapping("/users/{id}/default-app-key")
    public Result<String> defaultAppKey(@PathVariable long id) {
        return Result.ok(accountService.defaultAppKeyOf(id));
    }
}
