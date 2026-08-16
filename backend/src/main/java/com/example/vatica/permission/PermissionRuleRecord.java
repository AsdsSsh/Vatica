package com.example.vatica.permission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 迭代 14：用户选择“记住”后落库的路径级授权。 */
@Entity
@Table(name = "permission_rule",
        uniqueConstraints = @UniqueConstraint(name = "uk_permission_owner_path_access",
                columnNames = { "userId", "path", "access" }),
        indexes = @Index(name = "idx_permission_owner", columnList = "userId"))
public class PermissionRuleRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false) private Long userId;
    @Column(nullable = false, updatable = false) private Long orgId;
    @Column(nullable = false, length = 1000) private String path;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) private FileAccess access;

    protected PermissionRuleRecord() { }
    public PermissionRuleRecord(Long userId, Long orgId, String path, FileAccess access) {
        this.userId = userId;
        this.orgId = orgId;
        this.path = path;
        this.access = access;
    }
    public String getPath() { return path; }
    public FileAccess getAccess() { return access; }
}
