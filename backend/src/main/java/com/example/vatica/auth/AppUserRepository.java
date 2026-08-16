package com.example.vatica.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** 迭代 13 I13-2：用户仓储。 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);
}
