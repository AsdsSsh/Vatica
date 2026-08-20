package com.example.vatica.knowledge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 22D：独立的 OpenAI-compatible embedding 调用，不依赖聊天框架。 */
public final class OpenAiEmbeddingGateway implements EmbeddingGateway {

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    public OpenAiEmbeddingGateway(KnowledgeProperties.OpenAi properties, int dimensions, ObjectMapper mapper) {
        if (properties.baseUrl().isBlank() || properties.apiKey().isBlank() || properties.model().isBlank()) {
            throw new IllegalStateException("操作失败：知识库 OpenAI Embedding 需要配置 base-url、api-key 和 model。");
        }
        String baseUrl = properties.baseUrl().replaceAll("/+$", "");
        this.endpoint = URI.create(baseUrl.endsWith("/embeddings") ? baseUrl : baseUrl + "/embeddings");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper = mapper;
        this.apiKey = properties.apiKey();
        this.model = properties.model();
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        try {
            String request = mapper.writeValueAsString(Map.of("model", model, "input", text == null ? "" : text));
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request)).build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("操作失败：Embedding 服务返回 HTTP " + response.statusCode() + "。");
            }
            JsonNode values = mapper.readTree(response.body()).path("data").path(0).path("embedding");
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalStateException("操作失败：Embedding 模型返回了空向量。");
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            if (vector.length != dimensions) {
                throw new IllegalStateException("操作失败：Embedding 维度为 " + vector.length
                        + "，但知识库配置要求 " + dimensions + "。");
            }
            return vector;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("操作失败：Embedding 请求被中断。", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException known) {
                throw known;
            }
            throw new IllegalStateException("操作失败：调用 Embedding 服务失败。" + e.getMessage(), e);
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
