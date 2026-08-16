package com.example.vatica.config;

import org.springframework.data.jpa.repository.JpaRepository;

/** 迭代 13 I13-3：模型凭据密文仓储。 */
public interface ModelCredentialRepository extends JpaRepository<ModelCredential, String> {
}
