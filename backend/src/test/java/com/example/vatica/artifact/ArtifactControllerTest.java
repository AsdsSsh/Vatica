package com.example.vatica.artifact;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 迭代 25C：统一产物查询 API 的来源约束和脱敏响应。 */
class ArtifactControllerTest {

    @Test
    void listsArtifactsByExplicitSubject() throws Exception {
        ArtifactService service = mock(ArtifactService.class);
        ArtifactView view = new ArtifactView("a1", "MEETING_PREPARATION", "prep-1", "DOCUMENT",
                "会议准备文档", "meeting-preparation-prep-1.md", "READY", "已生成", "document",
                "meeting-preparation:prep-1:document", "2026-08-21T10:00:00Z", "2026-08-21T10:00:00Z");
        when(service.list("MEETING_PREPARATION", "prep-1")).thenReturn(List.of(view));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ArtifactController(service)).build();

        mvc.perform(get("/api/artifacts").param("subjectType", "MEETING_PREPARATION").param("subjectId", "prep-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("DOCUMENT"))
                .andExpect(jsonPath("$[0].status").value("READY"))
                .andExpect(jsonPath("$[0].locator").value("meeting-preparation-prep-1.md"));

        verify(service).list(eq("MEETING_PREPARATION"), eq("prep-1"));
    }
}
