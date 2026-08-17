package com.example.vatica.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import com.example.vatica.config.AppStateProperties;
import com.example.vatica.secret.FileMasterKeyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 迭代 13 I13-2：JWT 签发/校验/过期/篡改。 */
class JwtServiceTest {

    @TempDir
    Path dir;

    @Test
    void issueAndVerifyRoundTrip() {
        JwtService jwt = jwt(Duration.ofHours(1));
        AppUser user = user();

        JwtService.Claims claims = jwt.verify(jwt.issue(user));

        assertThat(claims.userId()).isEqualTo(7L);
        assertThat(claims.orgId()).isEqualTo(7L);
        assertThat(claims.role()).isEqualTo(AppUser.ROLE_ORG_ADMIN);
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void tamperedSignatureRejected() {
        JwtService jwt = jwt(Duration.ofHours(1));
        String token = jwt.issue(user());

        int signatureStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(signatureStart);
        String tampered = token.substring(0, signatureStart) + (first == 'A' ? 'B' : 'A')
                + token.substring(signatureStart + 1);

        assertThatThrownBy(() -> jwt.verify(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("签名");
    }

    @Test
    void expiredTokenRejected() {
        JwtService jwt = jwt(Duration.ofSeconds(-1));

        assertThatThrownBy(() -> jwt.verify(jwt.issue(user())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过期");
    }

    private AppUser user() {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(7L);
        when(user.getOrgId()).thenReturn(7L);
        when(user.getRole()).thenReturn(AppUser.ROLE_ORG_ADMIN);
        when(user.getUsername()).thenReturn("alice");
        return user;
    }

    private JwtService jwt(Duration ttl) {
        FileMasterKeyProvider masterKey = new FileMasterKeyProvider(new AppStateProperties(dir.toString()));
        return new JwtService(masterKey, new ObjectMapper(), ttl);
    }
}
