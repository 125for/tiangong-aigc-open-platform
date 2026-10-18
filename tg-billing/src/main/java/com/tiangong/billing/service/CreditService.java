package com.tiangong.billing.service;

import com.tiangong.billing.dto.Dtos;
import com.tiangong.billing.dto.Dtos.CreditOpVO;
import com.tiangong.billing.dto.Dtos.HoldStatusVO;
import com.tiangong.billing.entity.CreditAccount;
import com.tiangong.billing.entity.CreditLedger;
import com.tiangong.billing.repo.CreditAccountRepository;
import com.tiangong.billing.repo.CreditLedgerRepository;
import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 积分账务核心。所有方法以 bizNo 幂等：
 *   hold    : balance-N, held+N   （任务提交预扣）
 *   settle  : held-N              （任务成功，预扣变实扣）
 *   refund  : held-N, balance+N   （失败/超时/取消，预扣退还）
 *   grant   : balance+N           （支付发货）
 *
 * 并发策略：单账户行悲观锁（短事务）串行化变更；credit_ledgers 唯一约束
 * (biz_type, biz_no, change_type) 作为幂等的最终防线，两层防线缺一不可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditService {

    public static final String BIZ_TASK = "TASK";
    public static final String BIZ_ORDER = "ORDER";

    private final CreditAccountRepository accountRepo;
    private final CreditLedgerRepository ledgerRepo;

    @Transactional
    public void grant(long userId, String bizType, String bizNo, int credits, String remark) {
        if (credits <= 0) {
            throw new BizException(ErrorCode.BAD_ARGUMENT, "积分数量必须为正");
        }
        if (ledgerRepo.existsByBizTypeAndBizNoAndChangeType(bizType, bizNo, "GRANT")) {
            return;   // 发货幂等：同一单只发一次
        }
        CreditAccount account = lockedAccount(userId);
        account.setBalance(account.getBalance() + credits);
        accountRepo.saveAndFlush(account);
        insertLedger(userId, "GRANT", credits, account.getBalance(), bizType, bizNo, remark);
    }

    @Transactional
    public CreditOpVO hold(long userId, String bizNo, int credits) {
        if (credits <= 0) {
            throw new BizException(ErrorCode.BAD_ARGUMENT, "预扣积分必须为正");
        }
        HoldStatusVO existing = holdStatus(bizNo);
        if (!"NONE".equals(existing.status())) {
            // 幂等：重复预扣请求返回当前状态（HELD/SETTLED/REFUNDED 均视为成功受理）
            return toVO(existing);
        }
        CreditAccount account = lockedAccount(userId);
        if (account.getBalance() < credits) {
            throw new BizException(ErrorCode.INSUFFICIENT_CREDITS,
                    "积分不足：可用 " + account.getBalance() + "，需要 " + credits);
        }
        account.setBalance(account.getBalance() - credits);
        account.setHeld(account.getHeld() + credits);
        accountRepo.saveAndFlush(account);
        try {
            insertLedger(userId, "HOLD", -credits, account.getBalance(), BIZ_TASK, bizNo, "任务预扣");
        } catch (DataIntegrityViolationException e) {
            // 并发同 bizNo 双跑（入口幂等检查与插入之间存在窗口），整体回滚由调用方重试
            throw new BizException(ErrorCode.CONFLICT, "预扣请求并发冲突，请重试");
        }
        return new CreditOpVO(bizNo, "HELD", account.getBalance(), account.getHeld());
    }

    @Transactional
    public CreditOpVO settle(String bizNo) {
        HoldStatusVO st = holdStatus(bizNo);
        if ("SETTLED".equals(st.status()) || "NONE".equals(st.status())) {
            return toVO(st);   // 幂等 / 无可结算项（迟到的 settle 不产生副作用）
        }
        if ("REFUNDED".equals(st.status())) {
            log.warn("[ledger] late settle after refund, bizNo={} — 已退款不再扣费", bizNo);
            return toVO(st);
        }
        CreditLedger hold = ledgerRepo
                .findFirstByBizTypeAndBizNoAndChangeType(BIZ_TASK, bizNo, "HOLD").orElseThrow();
        CreditAccount account = lockedAccount(hold.getUserId());
        account.setHeld(account.getHeld() - st.credits());
        accountRepo.saveAndFlush(account);
        insertLedger(hold.getUserId(), "SETTLE", 0, account.getBalance(), BIZ_TASK, bizNo,
                "预扣结算 held-" + st.credits());
        return new CreditOpVO(bizNo, "SETTLED", account.getBalance(), account.getHeld());
    }

    @Transactional
    public CreditOpVO refund(String bizNo) {
        HoldStatusVO st = holdStatus(bizNo);
        if ("REFUNDED".equals(st.status()) || "NONE".equals(st.status())) {
            return toVO(st);
        }
        if ("SETTLED".equals(st.status())) {
            log.warn("[ledger] refund after settle rejected, bizNo={}", bizNo);
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID, "已结算预扣不可退款");
        }
        CreditLedger hold = ledgerRepo
                .findFirstByBizTypeAndBizNoAndChangeType(BIZ_TASK, bizNo, "HOLD").orElseThrow();
        CreditAccount account = lockedAccount(hold.getUserId());
        account.setHeld(account.getHeld() - st.credits());
        account.setBalance(account.getBalance() + st.credits());
        accountRepo.saveAndFlush(account);
        insertLedger(hold.getUserId(), "REFUND", st.credits(), account.getBalance(),
                BIZ_TASK, bizNo, "任务退款");
        return new CreditOpVO(bizNo, "REFUNDED", account.getBalance(), account.getHeld());
    }

    public HoldStatusVO holdStatus(String bizNo) {
        var hold = ledgerRepo.findFirstByBizTypeAndBizNoAndChangeType(BIZ_TASK, bizNo, "HOLD");
        if (hold.isEmpty()) {
            return new HoldStatusVO(bizNo, "NONE", 0);
        }
        int credits = Math.abs(hold.get().getAmount());
        if (ledgerRepo.existsByBizTypeAndBizNoAndChangeType(BIZ_TASK, bizNo, "SETTLE")) {
            return new HoldStatusVO(bizNo, "SETTLED", credits);
        }
        if (ledgerRepo.existsByBizTypeAndBizNoAndChangeType(BIZ_TASK, bizNo, "REFUND")) {
            return new HoldStatusVO(bizNo, "REFUNDED", credits);
        }
        return new HoldStatusVO(bizNo, "HELD", credits);
    }

    public Dtos.CreditAccountVO accountOf(long userId) {
        return accountRepo.findByUserId(userId)
                .map(a -> new Dtos.CreditAccountVO(userId, a.getBalance(), a.getHeld()))
                .orElseGet(() -> new Dtos.CreditAccountVO(userId, 0, 0));
    }

    /** 对账：流水重放余额 vs 账户快照（巡检用）。 */
    public boolean consistent(long userId) {
        var acc = accountRepo.findByUserId(userId);
        return acc.map(a -> ledgerRepo.replayBalance(userId) == a.getBalance()).orElse(true);
    }

    private CreditAccount lockedAccount(long userId) {
        var found = accountRepo.findByUserIdForUpdate(userId);
        if (found.isPresent()) {
            return found.get();
        }
        CreditAccount na = new CreditAccount();
        na.setUserId(userId);
        accountRepo.saveAndFlush(na);   // 罕见并发建户冲突 → 事务回滚，调用方幂等重试
        return accountRepo.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("credit account missing after create"));
    }

    private void insertLedger(long userId, String changeType, int amount, int balanceAfter,
                              String bizType, String bizNo, String remark) {
        CreditLedger l = new CreditLedger();
        l.setUserId(userId);
        l.setChangeType(changeType);
        l.setAmount(amount);
        l.setBalanceAfter(balanceAfter);
        l.setBizType(bizType);
        l.setBizNo(bizNo);
        l.setRemark(remark);
        ledgerRepo.saveAndFlush(l);
    }

    private CreditOpVO toVO(HoldStatusVO st) {
        return new CreditOpVO(st.bizNo(), st.status(), 0, 0);
    }
}
