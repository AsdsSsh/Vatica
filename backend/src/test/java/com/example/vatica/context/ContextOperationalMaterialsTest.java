package com.example.vatica.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.vatica.model.ConversationMessage;

/** 迭代 33：摘要失败降级时的操作材料渲染边界——四类必呈现、缺失不等于未发生、读取失败要标注。 */
class ContextOperationalMaterialsTest {

    @Test
    void emptyButRequiredMaterialsStillRenderAllCategoryBoundaries() {
        ContextOperationalMaterials materials = ContextOperationalMaterials.empty(true);

        assertThat(materials.shouldInject()).isTrue();
        String rendered = materials.render();
        assertThat(rendered).contains("【关键工具、审批和交付物记录】");
        assertThat(rendered).contains("任务和当前状态");
        assertThat(rendered).contains("关键工具调用");
        assertThat(rendered).contains("审批记录");
        assertThat(rendered).contains("交付物记录");
        // 每个类别缺失时都必须显式输出“无可用记录（不代表未发生）”，不能让模型把缺失当成已确认事实。
        assertThat(rendered.split("无可用记录（不代表未发生）", -1).length - 1).isEqualTo(4);
        assertThat(materials.contextMessage().role()).isEqualTo(ConversationMessage.user("").role());
        assertThat(materials.estimatedTokens()).isPositive();
    }

    @Test
    void notRequiredMaterialsAreNotInjected() {
        ContextOperationalMaterials materials = new ContextOperationalMaterials(
                List.of(new ContextOperationalMaterials.Snippet(
                        ContextOperationalMaterials.TOOL, "task:t1/tool:calendar.search", "SUCCESS", "查询完成")),
                false, false);

        assertThat(materials.shouldInject()).isFalse();
        assertThat(materials.snippets()).hasSize(1);
    }

    @Test
    void renderMarksLookupFailureInsteadOfPretendingCompleteness() {
        ContextOperationalMaterials materials = new ContextOperationalMaterials(List.of(), true, true);

        assertThat(materials.render()).contains("读取部分操作记录失败");
        assertThat(materials.render()).contains("不能按“无记录”处理");
    }

    @Test
    void renderKeepsSnippetFieldsAndCategoryOrder() {
        List<ContextOperationalMaterials.Snippet> snippets = List.of(
                new ContextOperationalMaterials.Snippet(ContextOperationalMaterials.ARTIFACT,
                        "DOCUMENT/会议准备文档", "READY", "locator=workspace://meeting.md"),
                new ContextOperationalMaterials.Snippet(ContextOperationalMaterials.APPROVAL,
                        "task:t1/step:2", "PENDING", "description=写入文档"),
                new ContextOperationalMaterials.Snippet(ContextOperationalMaterials.TOOL,
                        "task:t1/tool:calendar.search", "SUCCESS", "output=返回 3 条日程"),
                new ContextOperationalMaterials.Snippet(ContextOperationalMaterials.TASK_STATE,
                        "task:t1", "", "status=RUNNING; currentStep=1"));

        String rendered = new ContextOperationalMaterials(snippets, true, false).render();

        int taskState = rendered.indexOf("任务和当前状态：");
        int tool = rendered.indexOf("关键工具调用：");
        int approval = rendered.indexOf("审批记录：");
        int artifact = rendered.indexOf("交付物记录：");
        assertThat(taskState).isLessThan(tool).isLessThan(approval).isLessThan(artifact);
        assertThat(rendered).contains("task:t1").contains("calendar.search")
                .contains("会议准备文档").contains("PENDING").contains("返回 3 条日程");
    }

    @Test
    void renderTruncatesOversizedMaterialsWithExplicitBoundary() {
        List<ContextOperationalMaterials.Snippet> snippets = new ArrayList<>();
        String[] categories = { ContextOperationalMaterials.TASK_STATE, ContextOperationalMaterials.TOOL,
                ContextOperationalMaterials.APPROVAL, ContextOperationalMaterials.ARTIFACT };
        for (String category : categories) {
            for (int i = 0; i < 10; i++) {
                snippets.add(new ContextOperationalMaterials.Snippet(category,
                        "k".repeat(180) + "-" + i, "SUCCESS", "d".repeat(360)));
            }
        }

        String rendered = new ContextOperationalMaterials(snippets, true, false).render();

        assertThat(rendered.length()).isLessThanOrEqualTo(6_100);
        assertThat(rendered).contains("操作记录已按预算截取");
        assertThat(rendered).contains("不得视为不存在");
    }

    @Test
    void nullAndBlankSnippetsAreSanitizedInsteadOfFailingRender() {
        ContextOperationalMaterials materials = new ContextOperationalMaterials(
                java.util.Arrays.asList(null, new ContextOperationalMaterials.Snippet(null, null, null, null)),
                true, false);

        assertThat(materials.snippets()).hasSize(1);
        assertThat(materials.snippets().getFirst().category()).isEqualTo(ContextOperationalMaterials.TASK_STATE);
        assertThat(materials.render()).contains("任务和当前状态");
    }

    @Test
    void snippetSanitizesControlCharactersAndLongFields() {
        ContextOperationalMaterials.Snippet snippet = new ContextOperationalMaterials.Snippet(
                ContextOperationalMaterials.TOOL, "k\rey\n", "st\ratus", "de\ntail " + "y".repeat(400));

        String rendered = new ContextOperationalMaterials(List.of(snippet), true, false).render();

        assertThat(rendered).doesNotContain("\r").doesNotContain("\n- key");
        assertThat(rendered).contains("…");
    }
}
