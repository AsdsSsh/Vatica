package com.example.vatica.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.vatica.action.ActionExecutionRecord;
import com.example.vatica.action.ActionExecutionRecordRepository;
import com.example.vatica.action.ActionPlanView;
import com.example.vatica.artifact.ArtifactRecord;
import com.example.vatica.artifact.ArtifactRepository;
import com.example.vatica.artifact.ArtifactStatus;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.task.BlackboardEntry;
import com.example.vatica.task.TaskPlan;
import com.example.vatica.task.TaskRecord;
import com.example.vatica.task.TaskRecordRepository;
import com.example.vatica.task.TaskStatus;
import com.example.vatica.trace.AgentTraceRecord;
import com.example.vatica.trace.AgentTraceRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 33：H2 验证降级材料的租户隔离、类别覆盖与读取失败边界。 */
@SpringBootTest(properties = {
        "vatica.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-context-operational;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class ContextOperationalMaterialServiceTest {

    private static final RequestIdentity ALICE = new RequestIdentity(7L, 3L, "USER", "alice");
    private static final RequestIdentity BOB = new RequestIdentity(8L, 4L, "USER", "bob");

    @Autowired
    private ContextOperationalMaterialService service;

    @Autowired
    private TaskRecordRepository tasks;

    @Autowired
    private AgentTraceRecordRepository traces;

    @Autowired
    private ActionExecutionRecordRepository actions;

    @Autowired
    private ArtifactRepository artifacts;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        actions.deleteAll();
        artifacts.deleteAll();
        traces.deleteAll();
        tasks.deleteAll();
        RequestIdentityContext.set(ALICE);
    }

    @AfterEach
    void tearDown() {
        RequestIdentityContext.clear();
    }

    @Test
    void resolvesTaskToolApprovalAndArtifactSnippetsWithinTenant() throws Exception {
        String planJson = planJson();
        tasks.save(new TaskRecord("t-1", 7L, 3L, "准备会议材料", TaskStatus.RUNNING, planJson, 1, null));
        traces.save(new AgentTraceRecord("tr-1", 7L, 3L, "t-1", 1, "trace-1",
                "calendar.search", "查询下周日程", "返回 3 条日程", 120, 800, "SUCCESS", null));
        saveAction("a-1", "t-1");
        artifacts.save(new ArtifactRecord("art-1", ALICE, "TASK", "t-1", "DOCUMENT",
                "meeting-prep", "会议准备文档", "workspace://meeting-prep.md", ArtifactStatus.READY,
                "已生成会议准备文档", "a-1", "idem-1"));

        ContextOperationalMaterials materials = service.resolveForChat("t-1", "t-1", true);

        assertThat(materials.shouldInject()).isTrue();
        assertThat(materials.lookupFailed()).isFalse();
        List<String> categories = materials.snippets().stream()
                .map(ContextOperationalMaterials.Snippet::category).distinct().toList();
        assertThat(categories).contains(ContextOperationalMaterials.TASK_STATE,
                ContextOperationalMaterials.TOOL, ContextOperationalMaterials.APPROVAL,
                ContextOperationalMaterials.ARTIFACT);
        String rendered = materials.render();
        assertThat(rendered).contains("task:t-1").contains("calendar.search")
                .contains("会议准备文档").contains("PENDING");
    }

    @Test
    void otherTenantRecordsAreInvisibleEvenWithSameSubjectId() {
        RequestIdentityContext.set(BOB);
        tasks.save(new TaskRecord("t-2", 8L, 4L, "另一个组织的任务", TaskStatus.RUNNING,
                "{\"steps\":[]}", 0, null));

        RequestIdentityContext.set(ALICE);
        ContextOperationalMaterials materials = service.resolveForChat("t-2", "t-2", true);

        assertThat(materials.render()).doesNotContain("另一个组织的任务");
    }

    @Test
    void requiredWithoutRecordsStillRendersExplicitBoundary() {
        ContextOperationalMaterials materials = service.resolveForChat("s-none", null, true);

        assertThat(materials.shouldInject()).isTrue();
        assertThat(materials.render()).contains("无可用记录（不代表未发生）");
    }

    @Test
    void notRequiredLookupReturnsEmptyInjection() {
        ContextOperationalMaterials materials = service.resolveForChat("s-none", null, false);

        assertThat(materials.shouldInject()).isFalse();
        assertThat(materials.snippets()).isEmpty();
    }

    @Test
    void missingIdentityMarksLookupFailedInsteadOfThrowing() {
        RequestIdentityContext.clear();

        ContextOperationalMaterials materials = service.resolveForChat("s-1", null, true);

        assertThat(materials.shouldInject()).isTrue();
        assertThat(materials.lookupFailed()).isTrue();
        assertThat(materials.render()).contains("读取部分操作记录失败");
    }

    private String planJson() throws Exception {
        TaskPlan plan = new TaskPlan();
        TaskPlan.TaskStep read = new TaskPlan.TaskStep(1, "查询下周日程", false);
        read.setRequiredTools(List.of("calendar.search"));
        read.setResult("已查询到 3 条日程");
        read.setResultDigest("已查询到 3 条日程");
        TaskPlan.TaskStep write = new TaskPlan.TaskStep(2, "写入会议准备文档", true);
        write.setRequiredTools(List.of("workspace.write"));
        plan.setSteps(List.of(read, write));
        plan.setBlackboard(List.of(BlackboardEntry.agent(BlackboardEntry.NEED_HELP, write,
                "需要用户确认写入范围", BlackboardEntry.OPEN)));
        return mapper.writeValueAsString(plan);
    }

    private void saveAction(String id, String subjectId) {
        ActionPlanView plan = new ActionPlanView("plan-1", "TASK", subjectId, 1, "APPROVED", List.of());
        ActionPlanView.ActionItemView action = new ActionPlanView.ActionItemView(
                "document", "WRITE_DOCUMENT", "保存会议准备文档", "当前用户工作区",
                "新增 meeting-prep.md", "已确认会议", "workspace:write", "MEDIUM",
                "idem-" + id, "PENDING", null, null);
        actions.save(new ActionExecutionRecord(id, ALICE, plan, action));
    }
}
