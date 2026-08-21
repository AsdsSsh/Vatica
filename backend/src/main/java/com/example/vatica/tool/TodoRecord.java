package com.example.vatica.tool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 迭代 14：按用户隔离的待办持久化行。 */
@Entity
@Table(name = "vatica_todo",
        uniqueConstraints = @UniqueConstraint(name = "uk_todo_owner_todo", columnNames = { "userId", "todoId" }),
        indexes = @Index(name = "idx_todo_owner_due", columnList = "userId,done,due"))
public class TodoRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Long orgId;

    /** 25B：动作恢复使用名称型 UUID 作为稳定外部标识，避免重复重试创建待办。 */
    @Column(nullable = false, length = 36)
    private String todoId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 10)
    private String due;

    @Column(nullable = false)
    private boolean done;

    @Column(nullable = false, length = 16)
    private String createdAt;

    protected TodoRecord() { }

    public TodoRecord(Long userId, Long orgId, TodoTools.Todo todo) {
        this.userId = userId;
        this.orgId = orgId;
        this.todoId = todo.id();
        this.title = todo.title();
        this.due = todo.due();
        this.done = todo.done();
        this.createdAt = todo.createdAt();
    }

    public TodoTools.Todo toTodo() {
        return new TodoTools.Todo(todoId, title, due, done, createdAt);
    }

    public Long getId() { return id; }
    public String getTodoId() { return todoId; }
}
