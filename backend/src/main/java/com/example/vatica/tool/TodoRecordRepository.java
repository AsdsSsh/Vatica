package com.example.vatica.tool;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface TodoRecordRepository extends JpaRepository<TodoRecord, Long> {
    List<TodoRecord> findByUserId(Long userId);

    Optional<TodoRecord> findByUserIdAndTodoId(Long userId, String todoId);

    @Transactional
    void deleteByUserId(Long userId);

    @Transactional
    void deleteByUserIdAndTodoId(Long userId, String todoId);
}
