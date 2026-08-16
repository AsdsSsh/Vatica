package com.example.vatica.config;

import org.springframework.data.jpa.repository.JpaRepository;

/** 迭代 13 I13-4：用户自配模型凭据密文仓储。 */
public interface UserModelCredentialRepository extends JpaRepository<UserModelCredential, String> {
}
