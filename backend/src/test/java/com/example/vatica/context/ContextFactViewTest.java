package com.example.vatica.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** 迭代 29B：对外事实视图不得泄漏受控 value/evidence 字段或租户主键。 */
class ContextFactViewTest {

    @Test
    void publicViewContainsOnlyAuditableLabelsAndNoRawPayload() {
        String[] names = Arrays.stream(ContextFactView.class.getRecordComponents())
                .map(RecordComponent::getName).toArray(String[]::new);

        assertThat(names).contains("id", "factKey", "displaySummary", "sourceType", "verificationState");
        assertThat(names).doesNotContain("valueJson", "valueHash", "evidenceRefsJson", "orgId", "userId");
    }

    @Test
    void mappingKeepsSummaryButDropsPayloadByConstruction() {
        ContextFactRecord record = new ContextFactRecord("f-1", 3L, 7L, ContextFactScopeType.CHAT_SESSION,
                "chat-1", null, null, "goal", 1, null, ContextFactType.TASK_GOAL,
                "{\"goal\":\"private payload\"}", "已确认目标", "hash",
                ContextFactTrustLevel.USER_CONFIRMED, ContextFactVerificationState.CURRENT,
                ContextFactSourceType.USER_INPUT, "input-1", null, null, "[]", Instant.now(), Instant.now(), null);

        ContextFactView view = ContextFactView.from(record);
        assertThat(view.displaySummary()).isEqualTo("已确认目标");
        assertThat(view.getClass().getRecordComponents()).extracting(RecordComponent::getName)
                .doesNotContain("valueJson", "evidenceRefsJson");
    }
}
