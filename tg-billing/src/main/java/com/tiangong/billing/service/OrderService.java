package com.tiangong.billing.service;

import com.tiangong.billing.client.AccountClient;
import com.tiangong.billing.dto.Dtos;
import com.tiangong.billing.entity.OrderEntity;
import com.tiangong.billing.entity.Plan;
import com.tiangong.billing.repo.OrderRepository;
import com.tiangong.billing.repo.PlanRepository;
import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import com.tiangong.common.util.IdGen;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final PlanRepository planRepo;
    private final OrderRepository orderRepo;
    private final AccountClient accountClient;

    @Value("${tg.order.ttl-minutes:15}")
    private int orderTtlMinutes;

    /** 下单前经熔断客户端校验用户有效：account 不可用时快速失败（宁可拒单不可裸奔）。 */
    public Dtos.OrderVO createOrder(long userId, String planCode) {
        var user = accountClient.getUser(userId);
        if (user == null || user.getData() == null || !"ACTIVE".equals(user.getData().status())) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        Plan plan = planRepo.findByCodeAndStatus(planCode, "ON_SALE")
                .orElseThrow(() -> new BizException(ErrorCode.PLAN_NOT_FOUND, "商品不存在或已下架"));
        OrderEntity order = new OrderEntity();
        order.setOrderNo(IdGen.orderNo());
        order.setUserId(userId);
        order.setPlanId(plan.getId());
        order.setAmountCents(plan.getPriceCents());
        order.setExpiresAt(LocalDateTime.now().plusMinutes(orderTtlMinutes));
        orderRepo.save(order);
        return toVO(order, plan);
    }

    public Dtos.OrderVO getByOrderNo(long userId, String orderNo) {
        OrderEntity order = orderRepo.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "订单不存在"));
        if (order.getUserId() != userId) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return toVO(order, planRepo.findById(order.getPlanId()).orElse(null));
    }

    public List<Dtos.PlanVO> onSalePlans() {
        return planRepo.findAllByStatusOrderByPriceCentsAsc("ON_SALE").stream()
                .map(p -> new Dtos.PlanVO(p.getCode(), p.getName(), p.getType(), p.getPriceCents(),
                        p.getCurrency(), p.getPeriodDays(), p.getMonthlyCredits(), p.getCredits(),
                        p.getDescription()))
                .toList();
    }

    /** 定时关闭超时未支付订单（条件更新，多实例安全）。 */
    @org.springframework.transaction.annotation.Transactional
    public int closeExpiredOrders() {
        int n = orderRepo.closeExpired(LocalDateTime.now());
        if (n > 0) {
            log.info("[order-sweeper] closed {} expired orders", n);
        }
        return n;
    }

    private Dtos.OrderVO toVO(OrderEntity o, Plan p) {
        return new Dtos.OrderVO(o.getOrderNo(),
                p == null ? null : p.getCode(), p == null ? null : p.getName(),
                o.getAmountCents(), o.getCurrency(), o.getStatus(), o.getExpiresAt(), o.getPaidAt());
    }
}
