package com.example.vatica.skill;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 内置 Skill 发布版本；同一 id+version 内容不可被覆盖。 */
@Entity
@Table(name = "vatica_skill_version", uniqueConstraints = @UniqueConstraint(
        name = "uk_skill_version_release", columnNames = { "skill_id", "version_no" }))
public class SkillVersionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_id", nullable = false, length = 64)
    private String skillId;

    @Column(name = "version_no", nullable = false, length = 32)
    private String version;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "agent_role", nullable = false, length = 32)
    private String agentRole;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "vatica_skill_version_tool", joinColumns = @JoinColumn(name = "skill_version_id"))
    @Column(name = "tool_name", nullable = false, length = 128)
    private Set<String> tools = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "vatica_skill_version_permission", joinColumns = @JoinColumn(name = "skill_version_id"))
    @Column(name = "permission_name", nullable = false, length = 128)
    private Set<String> permissions = new LinkedHashSet<>();

    @Column(name = "entry_prompt", nullable = false, columnDefinition = "text")
    private String entryPrompt;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(nullable = false, length = 255)
    private String source;

    @Column(name = "released_at", nullable = false)
    private Instant releasedAt;

    protected SkillVersionRecord() {
    }

    public SkillVersionRecord(SkillManifestLoader.LoadedManifest loaded) {
        SkillManifest manifest = loaded.manifest();
        this.skillId = manifest.id();
        this.version = manifest.version();
        this.displayName = manifest.displayName();
        this.description = manifest.description();
        this.agentRole = manifest.agentRole();
        this.tools = new LinkedHashSet<>(manifest.tools());
        this.permissions = new LinkedHashSet<>(manifest.permissions());
        this.entryPrompt = manifest.entryPrompt();
        this.checksum = loaded.checksum();
        this.source = loaded.source() == null ? "classpath" : loaded.source();
        this.releasedAt = Instant.parse(manifest.releasedAt());
    }

    public Long getId() { return id; }
    public String getSkillId() { return skillId; }
    public String getVersion() { return version; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getAgentRole() { return agentRole; }
    public Set<String> getTools() { return Set.copyOf(tools); }
    public Set<String> getPermissions() { return Set.copyOf(permissions); }
    public String getEntryPrompt() { return entryPrompt; }
    public String getChecksum() { return checksum; }
    public String getSource() { return source; }
    public Instant getReleasedAt() { return releasedAt; }
}
