package com.example.vatica.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.vatica.trace.AgentTraceRecord;
import com.example.vatica.trace.AgentTraceRecordRepository;
import com.example.vatica.controller.ForbiddenException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 迭代 15 I15-1：任务执行轨迹接口——先校验任务归属，再返回脱敏摘要。 */
class TaskControllerTraceTest {

    @Test
    void tracesEndpointReturnsTaskTracesAfterOwnershipCheck() throws Exception {
        TaskService taskService = mock(TaskService.class);
        TaskEventPublisher eventPublisher = mock(TaskEventPublisher.class);
        AgentTraceRecordRepository traces = mock(AgentTraceRecordRepository.class);
        TaskRecord record = new TaskRecord("t1", 1L, 1L, "目标", TaskStatus.DONE, "{}", 0, null);
        when(taskService.get("t1")).thenReturn(record);
        when(traces.findByTaskIdOrderByCreatedAtAscIdAsc("t1")).thenReturn(List.of(
                new AgentTraceRecord("tr1", 1L, 1L, "t1", 2, "trace-9",
                        "workspace", "工作区 Agent", "read_file",
                        "{\"path\":\"/a.txt\",\"apiKey\":\"***\"}", "文件内容",
                        "workspace-files", "1.0.0", List.of("workspace:read", "workspace:write"),
                        4, 12, AgentTraceRecord.STATUS_SUCCESS, null)));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new TaskController(taskService, eventPublisher, new ObjectMapper(), traces)).build();

        String body = mvc.perform(get("/api/task/t1/traces"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        verify(taskService).get("t1");
        assertThat(body)
                .contains("\"toolName\":\"read_file\"")
                .contains("\"stepId\":2")
                .contains("\"traceId\":\"trace-9\"")
                .contains("\"skillId\":\"workspace-files\"")
                .contains("\"skillVersion\":\"1.0.0\"")
                .contains("\"skillPermissions\":[\"workspace:read\",\"workspace:write\"]")
                .contains("\"durationMs\":12")
                .doesNotContain("sk-");
    }

    @Test
    void ownershipFailureDoesNotQueryAnotherTenantsAuditRows() {
        TaskService taskService = mock(TaskService.class);
        AgentTraceRecordRepository traces = mock(AgentTraceRecordRepository.class);
        when(taskService.get("other-task")).thenThrow(new ForbiddenException("无权访问该任务"));
        TaskController controller = new TaskController(taskService, mock(TaskEventPublisher.class),
                new ObjectMapper(), traces);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.traces("other-task"))
                .isInstanceOf(ForbiddenException.class);
        verify(traces, never()).findByTaskIdOrderByCreatedAtAscIdAsc("other-task");
    }
}
