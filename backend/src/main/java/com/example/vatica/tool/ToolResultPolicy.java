package com.example.vatica.tool;

/**
 * 迭代 15 I15-10：工具输出预算——单次输出上限 8000 字符，超限保留头部 + 尾部并打截断标记；
 * 小结构输出原样通过。截断只影响喂给模型的内容，原始输出不会进入 trace 摘要。
 */
public final class ToolResultPolicy {

    public static final int MAX_OUTPUT_CHARS = 8_000;
    public static final int HEAD_CHARS = 6_000;
    public static final int TAIL_CHARS = 1_900;

    private ToolResultPolicy() {
    }

    public static String limit(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, HEAD_CHARS)
                + "\n…（工具输出已截断，共 " + output.length() + " 字符；如需完整数据请换更精确的查询）…\n"
                + output.substring(output.length() - TAIL_CHARS);
    }

    /** 迭代 20D：Skill 可在全局 8000 字符硬上限内进一步收窄单次输出。 */
    public static String limit(String output, int maxChars) {
        if (maxChars >= MAX_OUTPUT_CHARS) {
            return limit(output);
        }
        if (maxChars < 512) {
            throw new IllegalArgumentException("工具输出上限不能小于 512 字符。");
        }
        if (output == null) {
            return "";
        }
        if (output.length() <= maxChars) {
            return output;
        }
        String marker = "\n...（Skill 工具输出已按 " + maxChars + " 字符上限截断，原始 "
                + output.length() + " 字符）...\n";
        int content = maxChars - marker.length();
        int head = content * 3 / 4;
        int tail = content - head;
        return output.substring(0, head) + marker + output.substring(output.length() - tail);
    }
}
