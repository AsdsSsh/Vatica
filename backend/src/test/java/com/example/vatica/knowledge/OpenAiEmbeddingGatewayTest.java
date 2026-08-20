package com.example.vatica.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class OpenAiEmbeddingGatewayTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void callsOpenAiCompatibleEmbeddingEndpointAndValidatesDimensions() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"data\":[{\"embedding\":[0.25,-0.5,0.75]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OpenAiEmbeddingGateway gateway = new OpenAiEmbeddingGateway(new KnowledgeProperties.OpenAi(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "secret", "text-embedding-test"),
                3, new ObjectMapper());

        assertThat(gateway.embed("Vatica")).containsExactly(0.25f, -0.5f, 0.75f);
        assertThat(requestBody.get()).contains("text-embedding-test", "Vatica");
    }
}
