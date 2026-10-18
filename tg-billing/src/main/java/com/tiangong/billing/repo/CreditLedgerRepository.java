package com.tiangong.billing.repo;

import com.tiangong.billing.entity.CreditAccount;
import com.tiangong.billing.entity.CreditLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CreditLedgerRepository extends JpaRepository<CreditLedger, Long> {

    boolean existsByBizTypeAndBizNoAndChangeType(String bizType, String bizNo, String changeType);

    Optional<CreditLedger> findFirstByBizTypeAndBizNoAndChangeType(String bizType, String bizNo, String changeType);

    List<CreditLedger> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, org.springframework.data.domain.Pageable pageable);

    List<CreditLedger> findByBizTypeAndBizNo(String bizType, String bizNo);

    /** 对账：按流水重放计算某用户余额（用于巡检/修复）。 */
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM CreditLedger l WHERE l.userId = :userId")
    int replayBalance(@Param("userId") long userId);
}
