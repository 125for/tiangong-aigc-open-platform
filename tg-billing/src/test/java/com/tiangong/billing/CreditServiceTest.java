package com.tiangong.billing;

import com.tiangong.billing.dto.Dtos.CreditOpVO;
import com.tiangong.billing.dto.Dtos.HoldStatusVO;
import com.tiangong.billing.service.CreditService;
import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 账务核心测试（F4/F2 相关）：
 *  - 并发预扣零丢失：20 线程抢占余额，成功扣减总额 == 初始额度；
 *  - hold/settle/refund 全链幂等；
 *  - settle 后退款被拒、refund 后迟到 settle 无副作用。
 */
@SpringBootTest
@ActiveProfiles("test")
class CreditServiceTest {

    @Autowired
    private CreditService creditService;

    private static long uid() {
        return 900000 + System.nanoTime() % 100000;
    }

    @Test
    void concurrentHoldKeepsLedgerExact() throws Exception {
        long userId = uid();
        creditService.grant(userId, CreditService.BIZ_ORDER, "ORDER-C1", 1000, "test");

        int threads = 20, cost = 60;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    creditService.hold(userId, "TASK-C1-" + idx, cost);
                    ok.incrementAndGet();
                } catch (BizException e) {
                    if (e.getErrorCode() == ErrorCode.INSUFFICIENT_CREDITS) {
                        insufficient.incrementAndGet();
                    } else {
                        throw new RuntimeException(e);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        // 1000 / 60 = 16 次成功（余 40），4 次因余额不足拒绝
        assertEquals(16, ok.get(), "success holds");
        assertEquals(4, insufficient.get(), "insufficient rejections");
        var account = creditService.accountOf(userId);
        assertEquals(40, account.balance());
        assertEquals(16 * cost, account.held());
        assertTrue(creditService.consistent(userId), "流水重放与快照一致");

        // 全部退款后余额复原
        for (int i = 0; i < threads; i++) {
            creditService.refund("TASK-C1-" + i);
        }
        account = creditService.accountOf(userId);
        assertEquals(1000, account.balance());
        assertEquals(0, account.held());
        assertTrue(creditService.consistent(userId));
    }

    @Test
    void holdSettleRefundIdempotent() {
        long userId = uid();
        String bizNo = "TASK-IDEM-" + System.nanoTime();
        creditService.grant(userId, CreditService.BIZ_ORDER, "ORDER-C2-" + bizNo, 500, "test");

        // 重复 hold：同 bizNo 幂等返回
        CreditOpVO h1 = creditService.hold(userId, bizNo, 100);
        CreditOpVO h2 = creditService.hold(userId, bizNo, 100);
        assertEquals("HELD", h1.status());
        assertEquals("HELD", h2.status());
        assertEquals(400, creditService.accountOf(userId).balance());
        assertEquals(100, creditService.accountOf(userId).held());

        // settle 幂等
        assertEquals("SETTLED", creditService.settle(bizNo).status());
        assertEquals("SETTLED", creditService.settle(bizNo).status());
        assertEquals(400, creditService.accountOf(userId).balance());
        assertEquals(0, creditService.accountOf(userId).held());

        // settle 后不可退款
        BizException ex = assertThrows(BizException.class, () -> creditService.refund(bizNo));
        assertEquals(ErrorCode.ORDER_STATUS_INVALID, ex.getErrorCode());
        assertEquals(400, creditService.accountOf(userId).balance());
        assertTrue(creditService.consistent(userId));
    }

    @Test
    void refundThenLateSettleIsSafe() {
        long userId = uid();
        String bizNo = "TASK-LATE-" + System.nanoTime();
        creditService.grant(userId, CreditService.BIZ_ORDER, "ORDER-C3-" + bizNo, 300, "test");
        creditService.hold(userId, bizNo, 300);
        assertEquals("REFUNDED", creditService.refund(bizNo).status());
        assertEquals("REFUNDED", creditService.refund(bizNo).status());   // 幂等
        assertEquals(300, creditService.accountOf(userId).balance());
        // 迟到的 settle 不扣费、不抛错
        assertEquals("REFUNDED", creditService.settle(bizNo).status());
        assertEquals(300, creditService.accountOf(userId).balance());
        assertTrue(creditService.consistent(userId));
    }

    @Test
    void holdStatusOfUnknownBiz() {
        HoldStatusVO st = creditService.holdStatus("TASK-NEVER-" + System.nanoTime());
        assertEquals("NONE", st.status());
        List.of();   // 无副作用
    }
}
