package com.example.vatica.tool;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface TodoRecordRepository extends JpaRepository<TodoRecord, Long> {
    List<TodoRecord> findByUserId(Long userId);

    @Transactional
    void deleteByUserId(Long userId);

    @Transactional
    void deleteByUserIdAndTodoId(Long userId, String todoId);
}
