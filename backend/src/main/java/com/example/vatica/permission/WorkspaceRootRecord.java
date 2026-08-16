package com.example.vatica.permission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 迭代 14：用户工作区根的服务端事实记录。 */
@Entity
@Table(name = "workspace_root",
        uniqueConstraints = @UniqueConstraint(name = "uk_workspace_owner_path", columnNames = { "userId", "path" }),
        indexes = @Index(name = "idx_workspace_owner", columnList = "userId"))
public class WorkspaceRootRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false) private Long userId;
    @Column(nullable = false, updatable = false) private Long orgId;
    @Column(nullable = false, length = 1000) private String path;
    @Column(nullable = false) private boolean readable;
    @Column(nullable = false) private boolean writable;

    protected WorkspaceRootRecord() { }

    public WorkspaceRootRecord(Long userId, Long orgId, String path, boolean readable, boolean writable) {
        this.userId = userId;
        this.orgId = orgId;
        this.path = path;
        this.readable = readable;
        this.writable = writable;
    }

    public String getPath() { return path; }
    public boolean isReadable() { return readable; }
    public boolean isWritable() { return writable; }
}
