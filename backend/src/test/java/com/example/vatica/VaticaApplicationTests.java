package com.example.vatica;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 全上下文冒烟（迭代 5 起加 H2 数据源——测试零外部依赖）：
 * MCP 客户端禁用（暂无远程连接配置，测试环境不依赖外部 MCP 服务）；数据源用 H2 MySQL 兼容模式。
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class VaticaApplicationTests {

	@Test
	void contextLoads() {
	}

}
