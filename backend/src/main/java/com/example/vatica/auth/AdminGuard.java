package com.example.vatica.auth;

import com.example.vatica.controller.ForbiddenException;

/** 平台管理员守卫（迭代 13.5）：全局模型/集成配置写操作只允许平台管理员。 */
public final class AdminGuard {

    private AdminGuard() {
    }

    public static void requirePlatformAdmin() {
        RequestIdentity identity = RequestIdentityContext.current();
        String role = identity == null ? "" : identity.role();
        if (!AppUser.ROLE_PLATFORM_ADMIN.equals(role) && !"LOCAL".equals(role)) {
            throw new ForbiddenException("操作失败：只有平台管理员可以修改全局配置。");
        }
    }
}
