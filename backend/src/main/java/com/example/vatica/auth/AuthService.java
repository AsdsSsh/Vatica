package com.example.vatica.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号服务（迭代 13 I13-2）：注册 = 自动建组织 + 建用户；登录 = 校验哈希 + 签发 JWT。
 */
@Service
public class AuthService {

    private final AppUserRepository users;
    private final OrgRepository orgs;
    private final PasswordHasher hasher;
    private final JwtService jwt;

    public AuthService(AppUserRepository users, OrgRepository orgs, PasswordHasher hasher, JwtService jwt) {
        this.users = users;
        this.orgs = orgs;
        this.hasher = hasher;
        this.jwt = jwt;
    }

    @Transactional
    public AuthResult register(String username, String password, String orgName) {
        String name = normalize(username);
        if (name.length() < 3 || name.length() > 64) {
            throw new IllegalArgumentException("操作失败：用户名长度需在 3-64 个字符之间。");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("操作失败：密码至少 6 位。");
        }
        if (users.existsByUsername(name)) {
            throw new IllegalArgumentException("操作失败：用户名已存在（" + name + "）。");
        }
        boolean first = orgs.count() == 0;
        Org org = orgs.save(new Org(orgName == null || orgName.isBlank() ? "我的组织" : orgName.trim()));
        AppUser user = users.save(new AppUser(name, hasher.hash(password), org.getId(),
                first ? AppUser.ROLE_PLATFORM_ADMIN : AppUser.ROLE_ORG_ADMIN));
        return new AuthResult(user, jwt.issue(user));
    }

    public AuthResult login(String username, String password) {
        String name = normalize(username);
        AppUser user = users.findByUsername(name)
                .orElseThrow(() -> new IllegalArgumentException("操作失败：用户名或密码错误。"));
        if (!hasher.verify(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("操作失败：用户名或密码错误。");
        }
        return new AuthResult(user, jwt.issue(user));
    }

    public record AuthResult(AppUser user, String token) {
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim();
    }
}
