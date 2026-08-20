package com.example.vatica.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;

import com.example.vatica.config.AppStateProperties;
import com.example.vatica.secret.FileMasterKeyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/** 迭代 13 I13-2：注册/登录/重复用户名/首个用户平台管理员。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@SpringBootTest(properties = {
        "vatica.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class AuthServiceTest {

    @TempDir
    Path dir;

    @Autowired
    AppUserRepository users;
    @Autowired
    OrgRepository orgs;

    private AuthService service;

    @BeforeEach
    void setUp() {
        PasswordHasher hasher = new PasswordHasher();
        JwtService jwt = new JwtService(new FileMasterKeyProvider(new AppStateProperties(dir.toString())),
                new ObjectMapper(), Duration.ofHours(1));
        service = new AuthService(users, orgs, hasher, jwt);
    }

    @Test
    void firstUserIsPlatformAdminAndCanLogin() {
        AuthService.AuthResult registered = service.register("alice", "secret123", "团队A");

        assertThat(registered.user().getRole()).isEqualTo(AppUser.ROLE_PLATFORM_ADMIN);
        assertThat(registered.token()).isNotBlank();

        AuthService.AuthResult loggedIn = service.login("alice", "secret123");
        assertThat(loggedIn.user().getId()).isEqualTo(registered.user().getId());
    }

    @Test
    void duplicateUsernameRejected() {
        service.register("bob", "secret123", "团队B");

        assertThatThrownBy(() -> service.register("bob", "secret456", "团队C"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void wrongPasswordRejected() {
        service.register("carol", "secret123", "团队C");

        assertThatThrownBy(() -> service.login("carol", "wrong"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名或密码错误");
    }
}
