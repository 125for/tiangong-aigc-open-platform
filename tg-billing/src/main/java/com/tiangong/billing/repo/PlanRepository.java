package com.tiangong.billing.repo;

import com.tiangong.billing.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    Optional<Plan> findByCodeAndStatus(String code, String status);

    List<Plan> findAllByStatusOrderByPriceCentsAsc(String status);
}
