package com.tiangong.billing.service;

import com.tiangong.billing.dto.Dtos;
import com.tiangong.billing.dto.Dtos.MockPayVO;
import com.tiangong.billing.dto.Dtos.OrderVO;
import com.tiangong.billing.dto.Dtos.PaymentCallbackReq;
import com.tiangong.billing.dto.Dtos.PaymentCallbackVO;
import com.tiangong.billing.entity.OrderEntity;
import com.tiangong.billing.entity.Payment;
import com.tiangong.billing.entity.Plan;
import com.tiangong.billing.entity.Subscription;
import com.tiangong.billing.repo.OrderRepository;
import com.tiangong.billing.repo.PaymentRepository;
import com.tiangong.billing.repo.PlanRepository;
import com.tiangong.billing.repo.SubscriptionRepository;
import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import com.tiangong.common.util.CryptoUtil;
import com.tiangong.common.util.IdGen;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 支付回调处理。幂等三防线：
 *  1) 验签（HMAC-SHA256，常量时间比较）；
 *  2) payments.payment_no 唯一约束：重复通知插入即冲突 → delivered=false；
 *  3) 订单状态机条件更新 CREATED→PAID：并发回调只有一次生效。
 * 发货（订阅生效/积分入账）与状态变更同事务，全成或全无。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    public static final String SIGN_HEADER = "X-Channel-Sign";

    private final PaymentRepository paymentRepo;
    private final OrderRepository orderRepo;
    private final PlanRepository planRepo;
    private final SubscriptionRepository subscriptionRepo;
    private final CreditService creditService;

    @Value("${tg.channel-hmac-key}")
    private String channelKey;

    @Value("${tg.payments.mock-enabled:true}")
    private boolean mockEnabled;

    @Transactional
    public Dtos.PaymentCallbackVO handleCallback(PaymentCallbackReq req, String signature) {
        String expected = CryptoUtil.hmacSha256Hex(channelKey,
                req.paymentNo() + "|" + req.orderNo() + "|" + req.amountCents());
        if (!CryptoUtil.constantTimeEquals(expected, signature)) {
            throw new BizException(ErrorCode.PAY_SIGN_INVALID);
        }
        OrderEntity order = orderRepo.findByOrderNo(req.orderNo())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "订单不存在"));
        if (req.amountCents() == null || order.getAmountCents() == null
                || req.amountCents().intValue() != order.getAmountCents().intValue()) {
            throw new BizException(ErrorCode.PAY_AMOUNT_MISMATCH,
                    "回调金额 " + req.amountCents() + " ≠ 订单金额 " + order.getAmountCents());
        }
        if ("CLOSED".equals(order.getStatus())) {
            // 订单已超时关闭：显式拒绝，渠道侧停止重试并走人工退款流程
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID, "订单已关闭，无法支付");
        }
        if (!"CREATED".equals(order.getStatus())) {
            return new Dtos.PaymentCallbackVO(order.getStatus(), false);   // 重放：已处理过
        }
        Payment payment = new Payment();
        payment.setPaymentNo(req.paymentNo());
        payment.setOrderNo(req.orderNo());
        payment.setAmountCents(req.amountCents());
        payment.setChannel(req.channel() == null ? "MOCK" : req.channel());
        try {
            paymentRepo.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            // 同一 payment_no 的并发重复通知：另一线程已受理
            return new Dtos.PaymentCallbackVO(order.getStatus(), false);
        }
        if (orderRepo.markPaid(req.orderNo(), LocalDateTime.now()) == 0) {
            return new Dtos.PaymentCallbackVO("PAID", false);
        }
        fulfill(order);
        log.info("[payment] order={} paid, paymentNo={} delivered", req.orderNo(), req.paymentNo());
        return new Dtos.PaymentCallbackVO("PAID", true);
    }

    /** 支付渠道模拟器：铸造一笔“已支付”通知（含签名），由调用方 POST 到回调接口触发真实流程。 */
    public MockPayVO mintMockPay(Dtos.OrderVO order) {
        if (!mockEnabled) {
            throw new BizException(ErrorCode.FORBIDDEN, "支付模拟器未开启");
        }
        String paymentNo = IdGen.paymentNo();
        var body = new PaymentCallbackReq(paymentNo, order.orderNo(), order.amountCents(), "MOCK");
        String sign = CryptoUtil.hmacSha256Hex(channelKey,
                paymentNo + "|" + order.orderNo() + "|" + order.amountCents());
        return new MockPayVO(paymentNo, sign, body);
    }

    private void fulfill(OrderEntity order) {
        Plan plan = planRepo.findById(order.getPlanId())
                .orElseThrow(() -> new IllegalStateException("plan missing: " + order.getPlanId()));
        if ("SUBSCRIPTION".equals(plan.getType())) {
            activateSubscription(order, plan);
            creditService.grant(order.getUserId(), CreditService.BIZ_ORDER,
                    order.getOrderNo() + ":S", plan.getMonthlyCredits(), "订阅发货 " + plan.getCode());
        } else {
            creditService.grant(order.getUserId(), CreditService.BIZ_ORDER,
                    order.getOrderNo() + ":P", plan.getCredits(), "积分包发货 " + plan.getCode());
        }
    }

    private void activateSubscription(OrderEntity order, Plan plan) {
        var existing = subscriptionRepo.findByUserIdAndPlanId(order.getUserId(), plan.getId());
        LocalDateTime now = LocalDateTime.now();
        if (existing.isPresent()) {
            Subscription sub = existing.get();
            LocalDateTime base = sub.getPeriodEnd() != null && sub.getPeriodEnd().isAfter(now)
                    ? sub.getPeriodEnd() : now;   // 未到期续费顺延，过期重开
            sub.setStatus("ACTIVE");
            sub.setPeriodEnd(base.plusDays(plan.getPeriodDays()));
            if (sub.getPeriodStart() == null || "CANCELED".equals(sub.getStatus())) {
                sub.setPeriodStart(now);
            }
            subscriptionRepo.save(sub);
        } else {
            Subscription sub = new Subscription();
            sub.setUserId(order.getUserId());
            sub.setPlanId(plan.getId());
            sub.setPeriodStart(now);
            sub.setPeriodEnd(now.plusDays(plan.getPeriodDays()));
            subscriptionRepo.save(sub);
        }
    }
}
