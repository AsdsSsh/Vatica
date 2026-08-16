package com.example.vatica.config;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** 迭代 13 I13-4：用户自配模型槽位仓储。 */
public interface UserModelSlotRepository extends JpaRepository<UserModelSlot, String> {

    List<UserModelSlot> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId);
}
