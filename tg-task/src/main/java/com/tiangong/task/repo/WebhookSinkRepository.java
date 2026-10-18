package com.tiangong.task.repo;

import com.tiangong.task.entity.WebhookSinkRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookSinkRepository extends JpaRepository<WebhookSinkRecord, Long> {

    List<WebhookSinkRecord> findTop20ByOrderByIdDesc();
}
