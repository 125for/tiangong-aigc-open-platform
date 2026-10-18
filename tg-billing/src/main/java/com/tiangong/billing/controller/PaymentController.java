package com.tiangong.billing.controller;

import com.tiangong.billing.dto.Dtos;
import com.tiangong.billing.service.OrderService;
import com.tiangong.billing.service.PaymentService;
import com.tiangong.common.web.CurrentUser;
import com.tiangong.common.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 支付回调（渠道→平台，HMAC 验签）与渠道模拟器（验收用）。 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final OrderService orderService;

    @Value("${tg.jwt.secret}")
    private String jwtSecret;

    /** 白名单公开端点（渠道服务器回调），幂等可重放。 */
    @PostMapping("/callback")
    public Result<Dtos.PaymentCallbackVO> callback(
            @Valid @RequestBody Dtos.PaymentCallbackReq req,
            @RequestHeader(value = PaymentService.SIGN_HEADER, required = false) String sign) {
        return Result.ok(paymentService.handleCallback(req, sign == null ? "" : sign));
    }

    /** 渠道模拟器：为我的订单铸造一笔已签名通知（不发货运，POST /callback 才触发）。 */
    @PostMapping("/mock-pay")
    public Result<Dtos.MockPayVO> mockPay(@Valid @RequestBody Dtos.MockPayReq req,
                                          HttpServletRequest request) {
        long userId = CurrentUser.userId(request, jwtSecret);
        Dtos.OrderVO order = orderService.getByOrderNo(userId, req.orderNo());
        return Result.ok(paymentService.mintMockPay(order));
    }
}
