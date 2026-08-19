package com.example.vatica.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.controller.ForbiddenException;

/** 迭代 20A：默认安装、版本切换/回滚、组织隔离和管理权限。 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-skills;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class SkillCatalogServiceTest {

    @Autowired
    SkillCatalogService service;
    @Autowired
    SkillInstallationRepository installations;
    @Autowired
    SkillVersionRepository versions;

    @BeforeEach
    void setUp() {
        RequestIdentityContext.set(identity(7L, 9L, "ORG_ADMIN"));
        installations.deleteAll();
        versions.deleteAll();
    }

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void firstVisitInstallsLatestBundledVersions() {
        var catalog = service.catalog();

        assertThat(catalog).hasSize(4).allMatch(SkillCatalogService.SkillView::enabled);
        assertThat(catalog).extracting(SkillCatalogService.SkillView::id)
                .containsExactlyInAnyOrder("knowledge-research", "document-delivery", "workspace-files",
                        "personal-productivity");
        var knowledge = skill(catalog, "knowledge-research");
        assertThat(knowledge.activeVersion()).isEqualTo("1.1.0");
        assertThat(knowledge.latestVersion()).isEqualTo("1.1.0");
        assertThat(knowledge.versions()).extracting(SkillCatalogService.SkillVersionView::version)
                .containsExactly("1.1.0", "1.0.0");
        assertThat(knowledge.limits()).isEqualTo(SkillResourceLimits.forTools(knowledge.tools()));
        assertThat(knowledge.versions()).allSatisfy(version ->
                assertThat(version.limits()).isEqualTo(SkillResourceLimits.forTools(version.tools())));
    }

    @Test
    void lifecycleStateIsIsolatedByOrganization() {
        service.catalog();
        assertThat(service.disable("knowledge-research").enabled()).isFalse();

        RequestIdentityContext.set(identity(8L, 10L, "ORG_ADMIN"));
        assertThat(skill(service.catalog(), "knowledge-research").enabled()).isTrue();

        RequestIdentityContext.set(identity(7L, 9L, "ORG_ADMIN"));
        assertThat(skill(service.catalog(), "knowledge-research").enabled()).isFalse();
    }

    @Test
    void activationKeepsPreviousVersionForRollback() {
        service.catalog();
        var downgraded = service.activate("knowledge-research", "1.0.0");

        assertThat(downgraded.activeVersion()).isEqualTo("1.0.0");
        assertThat(downgraded.previousVersion()).isEqualTo("1.1.0");
        var restored = service.rollback("knowledge-research");
        assertThat(restored.activeVersion()).isEqualTo("1.1.0");
        assertThat(restored.previousVersion()).isEqualTo("1.0.0");
    }

    @Test
    void memberCanViewButCannotMutateLifecycle() {
        RequestIdentityContext.set(identity(11L, 9L, "MEMBER"));
        assertThat(service.catalog()).hasSize(4).allMatch(skill -> !skill.manageable());

        assertThatThrownBy(() -> service.disable("knowledge-research"))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("组织管理员");
    }

    @Test
    void pinnedExecutionVersionSurvivesUpgradeButDisableBlocksIt() {
        RequestIdentity identity = identity(7L, 9L, "ORG_ADMIN");
        var pinned = service.resolveForExecution(identity, "research", null, null).orElseThrow();
        assertThat(pinned.id()).isEqualTo("knowledge-research");
        assertThat(pinned.version()).isEqualTo("1.1.0");

        service.activate("knowledge-research", "1.0.0");
        assertThat(service.resolveForExecution(identity, "research", null, null).orElseThrow().version())
                .isEqualTo("1.0.0");
        assertThat(service.resolveForExecution(identity, "research", pinned.id(), pinned.version())
                .orElseThrow().version()).isEqualTo("1.1.0");

        service.disable("knowledge-research");
        assertThatThrownBy(() -> service.resolveForExecution(identity, "research", pinned.id(), pinned.version()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已停用");
    }

    @Test
    void failedActivationLeavesLifecycleStateUntouched() {
        service.catalog();

        assertThatThrownBy(() -> service.activate("knowledge-research", "9.9.9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不存在版本");

        var current = skill(service.catalog(), "knowledge-research");
        assertThat(current.activeVersion()).isEqualTo("1.1.0");
        assertThat(current.previousVersion()).isNull();
    }

    @Test
    void rollbackChangesNewDefaultButKeepsPinnedReleaseExecutable() {
        RequestIdentity identity = identity(7L, 9L, "ORG_ADMIN");
        service.catalog();
        service.activate("knowledge-research", "1.0.0");
        var pinned = service.resolveForExecution(identity, "research", null, null).orElseThrow();

        assertThat(service.rollback("knowledge-research").activeVersion()).isEqualTo("1.1.0");
        assertThat(service.resolveForExecution(identity, "research", null, null).orElseThrow().version())
                .isEqualTo("1.1.0");
        assertThat(service.resolveForExecution(identity, "research", pinned.id(), pinned.version())
                .orElseThrow().version()).isEqualTo("1.0.0");
    }

    private static SkillCatalogService.SkillView skill(
            java.util.List<SkillCatalogService.SkillView> values, String id) {
        return values.stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    private static RequestIdentity identity(long userId, long orgId, String role) {
        return new RequestIdentity(userId, orgId, role, "user-" + userId);
    }
}
