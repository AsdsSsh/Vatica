package com.example.vatica.skill;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/** 组织级 Skill 激活指针；发布版本本体保持不可变。 */
@Entity
@Table(name = "vatica_skill_installation", uniqueConstraints = @UniqueConstraint(
        name = "uk_skill_installation_org", columnNames = { "org_id", "skill_id" }))
public class SkillInstallationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "skill_id", nullable = false, length = 64)
    private String skillId;

    @Column(name = "active_version", nullable = false, length = 32)
    private String activeVersion;

    @Column(name = "previous_version", length = 32)
    private String previousVersion;

    @Column(nullable = false)
    private boolean enabled;

    @Version
    @Column(nullable = false)
    private long revision;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SkillInstallationRecord() {
    }

    public SkillInstallationRecord(Long orgId, String skillId, String activeVersion, Long updatedBy) {
        this.orgId = orgId;
        this.skillId = skillId;
        this.activeVersion = activeVersion;
        this.enabled = true;
        touch(updatedBy);
    }

    public void enable(Long userId) {
        enabled = true;
        touch(userId);
    }

    public void disable(Long userId) {
        enabled = false;
        touch(userId);
    }

    public void activate(String version, Long userId) {
        if (!activeVersion.equals(version)) {
            previousVersion = activeVersion;
            activeVersion = version;
        }
        enabled = true;
        touch(userId);
    }

    public void rollback(Long userId) {
        if (previousVersion == null) {
            throw new IllegalArgumentException("操作失败：该 Skill 没有可回滚版本。");
        }
        String target = previousVersion;
        previousVersion = activeVersion;
        activeVersion = target;
        enabled = true;
        touch(userId);
    }

    private void touch(Long userId) {
        this.updatedBy = userId;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOrgId() { return orgId; }
    public String getSkillId() { return skillId; }
    public String getActiveVersion() { return activeVersion; }
    public String getPreviousVersion() { return previousVersion; }
    public boolean isEnabled() { return enabled; }
    public long getRevision() { return revision; }
    public Long getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
