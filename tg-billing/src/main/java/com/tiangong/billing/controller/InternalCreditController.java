package com.tiangong.billing.controller;

import com.tiangong.billing.dto.Dtos;
import com.tiangong.billing.service.CreditService;
import com.tiangong.common.web.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 积分账务内部接口（tg-task 调用）：预扣/结算/退款全部以 bizNo 幂等。 */
@RestController
@RequestMapping("/internal/v1/credits")
@RequiredArgsConstructor
public class InternalCreditController {

    private final CreditService creditService;

    @PostMapping("/hold")
    public Result<Dtos.CreditOpVO> hold(@Valid @RequestBody Dtos.CreditOpReq req) {
        return Result.ok(creditService.hold(req.userId(), req.bizNo(), req.credits()));
    }

    @PostMapping("/settle")
    public Result<Dtos.CreditOpVO> settle(@Valid @RequestBody Dtos.SettleReq req) {
        return Result.ok(creditService.settle(req.bizNo()));
    }

    @PostMapping("/refund")
    public Result<Dtos.CreditOpVO> refund(@Valid @RequestBody Dtos.SettleReq req) {
        return Result.ok(creditService.refund(req.bizNo()));
    }

    @GetMapping("/holds/{bizNo}")
    public Result<Dtos.HoldStatusVO> holdStatus(@PathVariable String bizNo) {
        return Result.ok(creditService.holdStatus(bizNo));
    }

    @GetMapping("/consistency/{userId}")
    public Result<Boolean> consistent(@PathVariable long userId) {
        return Result.ok(creditService.consistent(userId));
    }
}
