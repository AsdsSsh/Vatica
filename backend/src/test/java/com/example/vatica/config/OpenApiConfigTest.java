package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.OpenAPI;

/** OpenAPI 契约配置单测（迭代 9 I9-2）：契约元信息（前后端契约唯一事实来源）。 */
class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void openApiCarriesContractMetadata() {
        OpenAPI openApi = config.vaticaOpenApi();
        assertThat(openApi.getInfo().getTitle()).isEqualTo("Vatica API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("1.0");
        assertThat(openApi.getInfo().getDescription()).contains("前后端分离");
    }
}
