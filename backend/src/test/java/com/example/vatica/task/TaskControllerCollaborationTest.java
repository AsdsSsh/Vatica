package com.example.vatica.task;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.vatica.trace.AgentTraceRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 17B：HumanAgent note HTTP 契约。 */
class TaskControllerCollaborationTest {

    @Test
    void postsHumanNoteAndReturnsUpdatedTaskSnapshot() throws Exception {
        TaskService service = mock(TaskService.class);
        TaskEventPublisher events = mock(TaskEventPublisher.class);
        AgentTraceRecordRepository traces = mock(AgentTraceRecordRepository.class);
        TaskRecord record = new TaskRecord("task-1", 7L, 9L, "整理报告", TaskStatus.PENDING_APPROVAL,
                "{\"steps\":[],\"blackboard\":[]}", 0, null);
        record.onCreate();
        when(service.addHumanNote("task-1", "先生成正文，再更新目录。"))
                .thenReturn(record);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new TaskController(service, events, new ObjectMapper(), traces)).build();

        mvc.perform(post("/api/task/task-1/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"先生成正文，再更新目录。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-1"))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        verify(service).addHumanNote("task-1", "先生成正文，再更新目录。");
    }
}
