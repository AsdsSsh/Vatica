package com.example.vatica.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeTextChunkerTest {

    private final KnowledgeTextChunker chunker = new KnowledgeTextChunker();
    private final KnowledgeProperties properties = new KnowledgeProperties(
            true, "local-hash", 32, 1024 * 1024, 100, 20, 2000, null);

    @Test
    void splitsDeterministicallyAndKeepsSourceOffsets() {
        String source = "# 方案\n" + "知识库权限隔离与引用追踪。".repeat(18) + "\n# 验收\n必须返回来源位置。";

        var first = chunker.chunk(source, properties);
        var second = chunker.chunk(source, properties);

        assertThat(first).isEqualTo(second).hasSizeGreaterThan(1);
        assertThat(first.get(0).heading()).isEqualTo("方案");
        assertThat(first).allSatisfy(chunk -> {
            assertThat(chunk.text()).isEqualTo(source.substring(chunk.startOffset(), chunk.endOffset()));
            assertThat(chunk.text().length()).isLessThanOrEqualTo(properties.chunkSize());
        });
        assertThat(first.get(1).startOffset()).isLessThan(first.get(0).endOffset());
    }
}
