package com.example.vatica.skill;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillInstallationRepository extends JpaRepository<SkillInstallationRecord, Long> {
    Optional<SkillInstallationRecord> findByOrgIdAndSkillId(Long orgId, String skillId);
    List<SkillInstallationRecord> findByOrgIdOrderBySkillIdAsc(Long orgId);
}
