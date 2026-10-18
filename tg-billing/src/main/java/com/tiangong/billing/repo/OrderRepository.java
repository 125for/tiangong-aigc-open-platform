package com.tiangong.billing.repo;

import com.tiangong.billing.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    Optional<OrderEntity> findByOrderNo(String orderNo);

    /** 条件更新构成状态机：仅 CREATED 可支付，返回影响行数判断是否竞争成功。 */
    @Modifying
    @Query("UPDATE OrderEntity o SET o.status = 'PAID', o.paidAt = :now WHERE o.orderNo = :orderNo AND o.status = 'CREATED'")
    int markPaid(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE OrderEntity o SET o.status = 'CLOSED' WHERE o.status = 'CREATED' AND o.expiresAt < :now")
    int closeExpired(@Param("now") LocalDateTime now);
}
