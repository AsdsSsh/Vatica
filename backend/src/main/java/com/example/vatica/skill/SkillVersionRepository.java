package com.example.vatica.skill;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillVersionRepository extends JpaRepository<SkillVersionRecord, Long> {
    Optional<SkillVersionRecord> findBySkillIdAndVersion(String skillId, String version);
    List<SkillVersionRecord> findBySkillId(String skillId);
    List<SkillVersionRecord> findAllByOrderBySkillIdAsc();
}
