package com.example.vatica.task;

import org.springframework.data.jpa.repository.JpaRepository;

/** 任务仓库（迭代 5 I5-4）。 */
public interface TaskRecordRepository extends JpaRepository<TaskRecord, String> {
}
