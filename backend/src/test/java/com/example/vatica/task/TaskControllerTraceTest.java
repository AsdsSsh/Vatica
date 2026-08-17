package com.example.vatica.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.vatica.trace.AgentTraceRecord;
import com.example.vatica.trace.AgentTraceRecordRepository;
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
                new AgentTraceRecord("tr1", 1L, 1L, "t1", 2, "trace-9", "read_file",
                        "{\"path\":\"/a.txt\",\"apiKey\":\"***\"}", "文件内容", 4, 12,
                        AgentTraceRecord.STATUS_SUCCESS, null)));

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
                .contains("\"durationMs\":12")
                .doesNotContain("sk-");
    }
}
