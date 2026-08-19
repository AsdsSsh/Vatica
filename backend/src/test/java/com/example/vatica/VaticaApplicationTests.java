package com.example.vatica;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 全上下文冒烟（迭代 5 起加 H2 数据源——测试零外部依赖）：
 * MCP 客户端禁用（暂无远程连接配置，测试环境不依赖外部 MCP 服务）；数据源用 H2 MySQL 兼容模式。
 * 迭代 14.5：补 OpenAPI 契约断言——/api/auth/me 与 CurrentUserResponse 进入契约。
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
@AutoConfigureMockMvc
class VaticaApplicationTests {

	@Autowired
	MockMvc mvc;

	@Test
	void contextLoads() {
	}

	/** 迭代 14.5：后端契约（DTO → OpenAPI）是前端 api.ts 的唯一事实来源。 */
	@Test
	void openApiContainsCurrentUserContract() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"/api/auth/me\"")))
				.andExpect(content().string(containsString("CurrentUserResponse")))
				.andExpect(content().string(containsString("\"expiresAt\"")));
	}

	/** 迭代 16：SSE 续传请求头进入 OpenAPI 契约，前端不得自行猜接口字段。 */
	@Test
	void openApiContainsSseResumeHeader() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("/api/task/{id}/events")))
				.andExpect(content().string(containsString("Last-Event-ID")));
	}

	/** 迭代 18C：评测归档字段与报告接口必须进入 OpenAPI，再由前端类型同步。 */
	@Test
	void openApiContainsEvaluationGateContract() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("/api/evaluation/report")))
				.andExpect(content().string(containsString("benchmarkCaseId")))
				.andExpect(content().string(containsString("EvaluationReport")));
	}

	/** 迭代 20D：Skill 资源额度与执行审计字段进入前后端唯一契约。 */
	@Test
	void openApiContainsSkillGovernanceContract() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("SkillResourceLimits")))
				.andExpect(content().string(containsString("maxToolCalls")))
				.andExpect(content().string(containsString("skillPermissions")));
	}

}
