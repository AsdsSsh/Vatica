package com.example.vatica.auth;

import org.springframework.data.jpa.repository.JpaRepository;

/** 迭代 13 I13-2：组织仓储。 */
public interface OrgRepository extends JpaRepository<Org, Long> {
}
