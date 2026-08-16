package com.example.vatica.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;

/** 迭代 12 热修：权限请求的 createdAt(Instant) 必须可被项目 ObjectMapper 序列化。 */
class PermissionEventPublisherTest {

    @Test
    void filePermissionRequestSerializesWithJavaTime() throws Exception {
        // 与 ChatConfig.objectMapper() 的注册方式保持一致
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        FilePermissionRequest request = new FilePermissionRequest("r1", "chat:s1", "C:\\x",
                FileAccess.READ, FilePermissionMode.WORKSPACE_WRITE, "读取说明", Instant.now());

        String json = mapper.writeValueAsString(request);

        assertThat(json).contains("\"createdAt\"");
        assertThat(json).doesNotContain("InvalidDefinitionException");
    }
}
