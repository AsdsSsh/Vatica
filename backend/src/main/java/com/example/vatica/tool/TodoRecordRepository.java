package com.example.vatica.tool;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface TodoRecordRepository extends JpaRepository<TodoRecord, Long> {
    List<TodoRecord> findByUserId(Long userId);

    /** 26A：周报事实收集必须同时按组织和用户收口。 */
    List<TodoRecord> findByOrgIdAndUserId(Long orgId, Long userId);

    Optional<TodoRecord> findByUserIdAndTodoId(Long userId, String todoId);

    @Transactional
    void deleteByUserId(Long userId);

    @Transactional
    void deleteByUserIdAndTodoId(Long userId, String todoId);
}
