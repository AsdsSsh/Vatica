package com.example.vatica.meeting;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.vatica.action.ActionExecutionService;
import com.example.vatica.artifact.ArtifactService;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.knowledge.KnowledgeBaseService;
import com.example.vatica.permission.FilePermissionMode;
import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.permission.PermissionPolicyService;
import com.example.vatica.permission.WorkspaceRoot;
import com.example.vatica.tool.CalendarEventRecordRepository;
import com.example.vatica.tool.TodoRecordRepository;
import com.example.vatica.workspace.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 25D：执行前重新读取服务端权限，权限回收不得被旧批准绕过。 */
class MeetingPreparationPermissionTest {

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void revokedWorkspaceWritePermissionBlocksExecutionEvenAfterApproval() {
        PermissionPolicyService permissions = mock(PermissionPolicyService.class);
        when(permissions.current()).thenReturn(new FilePermissionPolicy(FilePermissionMode.READ_ONLY,
                List.of(new WorkspaceRoot(Path.of("workspace").toAbsolutePath().toString(), true, false))));
        MeetingPreparationService service = new MeetingPreparationService(
                mock(CalendarEventRecordRepository.class), mock(MeetingPreparationRecordRepository.class),
                mock(KnowledgeBaseService.class), new ObjectMapper(), mock(WorkspaceStore.class),
                mock(TodoRecordRepository.class), mock(ActionExecutionService.class), mock(ArtifactService.class), permissions);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "ensureWorkspaceWriteStillAllowed"))
                .hasMessageContaining("写权限已被回收");
    }
}
