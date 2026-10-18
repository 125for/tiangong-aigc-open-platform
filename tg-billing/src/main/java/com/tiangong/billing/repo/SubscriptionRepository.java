package com.tiangong.billing.repo;

import com.tiangong.billing.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByUserIdAndPlanId(Long userId, Long planId);
}
