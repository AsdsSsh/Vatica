package com.example.vatica;

import java.io.IOException;
import java.nio.file.Path;

import com.example.vatica.config.AppStateMigration;
import com.example.vatica.config.AppStateProperties;
import com.example.vatica.config.WindowsRegistryEnvBackfill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class VaticaApplication {

	public static void main(String[] args) throws IOException {
		// setx 只写注册表，运行中的 shell/IDE 拿不到新值（打包版由 Rust 启动器回填，
		// 开发模式这里补同一策略，避免 MYSQL_PASSWORD 缺失导致启动失败）
		WindowsRegistryEnvBackfill.backfill();
		// 迭代 11：data/ → 工作区根 + .vatica/（必须在 JPA/H2 初始化前完成）
		AppStateMigration.run(Path.of("."), Path.of("data"),
				Path.of(System.getProperty("vatica.app.state-dir", AppStateProperties.DEFAULT_STATE_DIR)));
		SpringApplication.run(VaticaApplication.class, args);
	}

}
