package com.tiangong.billing.repo;

import com.tiangong.billing.entity.CreditAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CreditAccountRepository extends JpaRepository<CreditAccount, Long> {

    Optional<CreditAccount> findByUserId(Long userId);

    /**
     * 悲观行锁（SELECT ... FOR UPDATE）：锁粒度=单账户行、持锁时间=单事务内几条写语句。
     * 账务不变式（balance>=0, held>=0）在锁内校验，避免乐观锁重试循环带来的复杂度；
     * 幂等仍由 credit_ledgers 唯一约束兜底（两层防线）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM CreditAccount a WHERE a.userId = :userId")
    Optional<CreditAccount> findByUserIdForUpdate(@Param("userId") Long userId);
}
