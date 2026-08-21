package com.example.vatica.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.vatica.action.ActionPlanView;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;

/** 迭代 25C：产物索引的用户隔离、状态映射和失败保留。 */
class ArtifactServiceTest {

    private final ArtifactRepository repository = org.mockito.Mockito.mock(ArtifactRepository.class);
    private final ArtifactService service = new ArtifactService(repository);
    private final RequestIdentity identity = new RequestIdentity(7L, 3L, "USER", "alice");

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void syncCreatesDraftDocumentTodosAndFailureAsSeparateArtifacts() {
        RequestIdentityContext.set(identity);
        when(repository.save(any(ArtifactRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.syncMeetingPreparation(identity, "prep-1", plan(), "FAILED", "待办写入失败，请重试该动作。");

        ArgumentCaptor<ArtifactRecord> saved = ArgumentCaptor.forClass(ArtifactRecord.class);
        verify(repository, times(4)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ArtifactRecord::getType)
                .containsExactlyInAnyOrder("DRAFT", "DOCUMENT", "TODO", "FAILURE");
        assertThat(saved.getAllValues()).filteredOn(record -> "DOCUMENT".equals(record.getType())).singleElement()
                .extracting(ArtifactRecord::getStatus).isEqualTo(ArtifactStatus.READY);
        assertThat(saved.getAllValues()).filteredOn(record -> "TODO".equals(record.getType())).singleElement()
                .extracting(ArtifactRecord::getStatus).isEqualTo(ArtifactStatus.FAILED);
        assertThat(saved.getAllValues()).filteredOn(record -> "FAILURE".equals(record.getType())).singleElement()
                .extracting(ArtifactRecord::getSummary).asString().contains("待办写入失败");
    }

    @Test
    void listRequiresExplicitSubjectAndOnlyUsesCurrentUserRepositoryQuery() {
        RequestIdentityContext.set(identity);
        when(repository.findByUserIdAndSubjectTypeAndSubjectIdOrderByUpdatedAtDesc(7L, "MEETING_PREPARATION", "p1"))
                .thenReturn(List.of());

        assertThat(service.list("MEETING_PREPARATION", "p1")).isEmpty();
        verify(repository).findByUserIdAndSubjectTypeAndSubjectIdOrderByUpdatedAtDesc(7L, "MEETING_PREPARATION", "p1");
        assertThatThrownBy(() -> service.list("", "p1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("来源类型");
    }

    private static ActionPlanView plan() {
        return new ActionPlanView("meeting-preparation:prep-1:v1", "MEETING_PREPARATION", "prep-1", 1, "FAILED",
                List.of(
                        new ActionPlanView.ActionItemView("document", "WRITE_DOCUMENT", "写文档", "workspace",
                                "新增文档", "会议", "workspace:write", "MEDIUM", "prep-1:document", "APPROVED",
                                "SUCCEEDED", "meeting-preparation-prep-1.md"),
                        new ActionPlanView.ActionItemView("todo-1", "CREATE_TODO", "建待办", "todo", "新增待办", "会议",
                                "pim:todo:write", "MEDIUM", "prep-1:todo:1", "APPROVED", "FAILED", null)));
    }
}
