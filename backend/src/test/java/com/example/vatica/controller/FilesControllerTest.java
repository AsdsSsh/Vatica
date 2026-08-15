package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.vatica.tool.FileToolProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CharacterEncodingFilter;

/**
 * 文件产物接口测试（迭代 10 I10-7）：错误路径也必须符合全局 {@code {message}} 契约。
 */
class FilesControllerTest {

    @TempDir
    Path workspace;

    private FilesController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FileToolProperties props = new FileToolProperties(workspace.toString(), 1024);
        controller = new FilesController(props);
        CharacterEncodingFilter filter = new CharacterEncodingFilter(StandardCharsets.UTF_8.name(), true);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .build();
    }

    /** 正常文件：inline 返回、内容一致。 */
    @Test
    void downloadExistingFileReturnsInlineContent() throws Exception {
        Files.writeString(workspace.resolve("周报.docx"), "hello");

        mockMvc.perform(get("/api/files/{name}", "周报.docx"))
                .andExpect(status().isOk())
                .andExpect(mvcResult -> assertThat(
                        mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("hello"));
    }

    /**
     * 白名单外路径：400 + 统一 message（旧实现返回空 body）。
     * 直接调用 controller 验证：MockMvc 的 URL 规范化会在进入 controller 前把 ../ 折叠，
     * 无法覆盖到 PathSecurityGuard 分支。
     */
    @Test
    void downloadTraversalReturnsUnifiedMessage() {
        var response = controller.download("../outside.txt");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiError body = (ApiError) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message()).contains("路径不在已授权目录内");
    }

    /** 不存在的文件：404 + 统一 message（旧实现返回空 body）。 */
    @Test
    void downloadMissingFileReturnsUnifiedMessage() throws Exception {
        mockMvc.perform(get("/api/files/{name}", "不存在.txt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("操作失败：文件不存在（不存在.txt）。"));
    }

    /** 工作目录内文件列表（非空场景）。 */
    @Test
    void listReturnsArtifacts() throws Exception {
        Files.writeString(workspace.resolve("周报.docx"), "hello");

        mockMvc.perform(get("/api/files").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(mvcResult -> assertThat(
                        mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("\"name\":\"周报.docx\""));
    }

    /** 迭代 10 V12：内部数据文件不出现在产物列表。 */
    @Test
    void listExcludesInternalDataFiles() throws Exception {
        Files.writeString(workspace.resolve("周报.docx"), "hello");
        Files.writeString(workspace.resolve("models.json"), "{}");
        Files.writeString(workspace.resolve("todos.json"), "[]");
        Files.writeString(workspace.resolve("calendar.ics"), "BEGIN:VCALENDAR");

        String body = mockMvc.perform(get("/api/files").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("周报.docx")
                .doesNotContain("models.json")
                .doesNotContain("todos.json")
                .doesNotContain("calendar.ics");
    }
}
