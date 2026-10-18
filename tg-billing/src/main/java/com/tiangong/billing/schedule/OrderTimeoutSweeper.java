package com.tiangong.billing.schedule;

import com.tiangong.billing.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 订单超时关闭：条件 UPDATE，天然多实例安全（谁抢到算谁的）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutSweeper {

    private final OrderService orderService;

    @Scheduled(fixedDelayString = "${tg.order.sweep-interval-ms:20000}", initialDelay = 5000)
    public void sweep() {
        try {
            orderService.closeExpiredOrders();
        } catch (Exception e) {
            log.error("[order-sweeper] sweep failed", e);   // 定时任务永不向调度器抛异常
        }
    }
}
