package com.tiangong.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record PlanVO(String code, String name, String type, int priceCents, String currency,
                         int periodDays, int monthlyCredits, int credits, String description) {
    }

    public record OrderCreateReq(@NotBlank String planCode) {
    }

    public record MockPayReq(@NotBlank String orderNo) {
    }

    public record OrderVO(String orderNo, String planCode, String planName, int amountCents,
                          String currency, String status, LocalDateTime expiresAt, LocalDateTime paidAt) {
    }

    public record PaymentCallbackReq(@NotBlank String paymentNo, @NotBlank String orderNo,
                                     @NotNull Integer amountCents, String channel) {
    }

    public record PaymentCallbackVO(String orderStatus, boolean delivered) {
    }

    /** 支付渠道模拟器出参：供验收脚本重放回调（演示幂等）。 */
    public record MockPayVO(String paymentNo, String signature, PaymentCallbackReq callbackBody) {
    }

    public record CreditAccountVO(long userId, int balance, int held) {
    }

    public record LedgerVO(String changeType, int amount, int balanceAfter, String bizType,
                           String bizNo, String remark, LocalDateTime createdAt) {
    }

    public record LedgerPageVO(List<LedgerVO> items, int page, int size, long total) {
    }

    // ---------------- internal（服务间） ----------------

    public record CreditOpReq(@NotNull Long userId, @NotBlank String bizNo, @NotNull Integer credits) {
    }

    public record SettleReq(@NotBlank String bizNo) {
    }

    /** HELD（有未终结预扣）/ SETTLED / REFUNDED / NONE */
    public record HoldStatusVO(String bizNo, String status, int credits) {
    }

    public record CreditOpVO(String bizNo, String status, int balance, int held) {
    }

    public record InternalUserVO(Long userId, String status) {
    }
}
