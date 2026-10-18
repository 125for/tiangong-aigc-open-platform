package com.tiangong.account.repo;

import com.tiangong.account.entity.App;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppRepository extends JpaRepository<App, Long> {

    Optional<App> findByAppKey(String appKey);

    List<App> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<App> findFirstByUserIdAndStatusOrderByCreatedAtAsc(Long userId, String status);
}
