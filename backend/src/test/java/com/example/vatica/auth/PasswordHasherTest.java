package com.example.vatica.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 迭代 13 I13-2：PBKDF2 密码哈希。 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashAndVerify() {
        String hash = hasher.hash("secret123");

        assertThat(hash).startsWith("pbkdf2$");
        assertThat(hasher.verify("secret123", hash)).isTrue();
        assertThat(hasher.verify("wrong", hash)).isFalse();
    }

    @Test
    void samePasswordProducesDifferentSalt() {
        assertThat(hasher.hash("same")).isNotEqualTo(hasher.hash("same"));
    }
}
