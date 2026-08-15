package com.example.vatica;

import com.example.vatica.config.WindowsRegistryEnvBackfill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class VaticaApplication {

	public static void main(String[] args) {
		// setx 只写注册表，运行中的 shell/IDE 拿不到新值（打包版由 Rust 启动器回填，
		// 开发模式这里补同一策略，避免 MYSQL_PASSWORD 缺失导致启动失败）
		WindowsRegistryEnvBackfill.backfill();
		SpringApplication.run(VaticaApplication.class, args);
	}

}
