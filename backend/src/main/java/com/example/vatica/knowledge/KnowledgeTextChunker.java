package com.example.vatica.knowledge;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** 可重复的字符窗口切片器：优先在换行/空格处断开，并保留有限重叠。 */
@Component
public class KnowledgeTextChunker {

    public List<Chunk> chunk(String source, KnowledgeProperties properties) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        String text = source.replace("\r\n", "\n").replace('\r', '\n');
        int size = properties.chunkSize();
        int overlap = properties.chunkOverlap();
        List<Chunk> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int proposedEnd = Math.min(text.length(), start + size);
            int end = boundary(text, start, proposedEnd);
            String value = text.substring(start, end).trim();
            if (!value.isBlank()) {
                int leftTrim = text.substring(start, end).indexOf(value);
                int actualStart = start + Math.max(leftTrim, 0);
                result.add(new Chunk(value, headingBefore(text, actualStart), actualStart, actualStart + value.length()));
            }
            if (end >= text.length()) {
                break;
            }
            int next = Math.max(end - overlap, start + 1);
            start = next;
        }
        return List.copyOf(result);
    }

    private static int boundary(String text, int start, int proposedEnd) {
        if (proposedEnd >= text.length()) {
            return text.length();
        }
        int min = start + Math.max(80, (proposedEnd - start) / 2);
        for (int i = proposedEnd; i > min; i--) {
            char ch = text.charAt(i - 1);
            if (ch == '\n' || ch == ' ' || ch == '。' || ch == '！' || ch == '？') {
                return i;
            }
        }
        return proposedEnd;
    }

    private static String headingBefore(String text, int start) {
        int headingStart = text.startsWith("#") ? 0 : -1;
        int previous = text.lastIndexOf("\n#", Math.max(0, start - 1));
        if (previous >= 0) {
            headingStart = previous + 1;
        }
        if (headingStart >= 0 && headingStart <= start) {
            int headingEnd = text.indexOf('\n', headingStart);
            String line = text.substring(headingStart, headingEnd < 0 ? text.length() : headingEnd).trim();
            return line.replaceFirst("^#+\\s*", "").trim();
        }
        return "";
    }

    public record Chunk(String text, String heading, int startOffset, int endOffset) {
    }
}
