package com.tiangong.task.repo;

import com.tiangong.task.entity.TaskEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {

    List<TaskEvent> findByTaskIdOrderByCreatedAtAscIdAsc(Long taskId);
}
