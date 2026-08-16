package com.example.vatica.permission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 用户文件权限模式；主键直接使用 userId，保证每用户一份。 */
@Entity
@Table(name = "permission_profile")
public class UserPermissionProfile {
    @Id private Long userId;
    @Column(nullable = false, updatable = false) private Long orgId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private FilePermissionMode mode;

    protected UserPermissionProfile() { }
    public UserPermissionProfile(Long userId, Long orgId, FilePermissionMode mode) {
        this.userId = userId;
        this.orgId = orgId;
        this.mode = mode;
    }
    public FilePermissionMode getMode() { return mode; }
    public void setMode(FilePermissionMode mode) { this.mode = mode; }
}
