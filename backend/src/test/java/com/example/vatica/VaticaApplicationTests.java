package com.example.vatica;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 全上下文冒烟：MCP 客户端在测试中禁用（演示连接指向 8081 天气服务，测试环境不启动）。 */
@SpringBootTest(properties = "spring.ai.mcp.client.enabled=false")
class VaticaApplicationTests {

	@Test
	void contextLoads() {
	}

}
