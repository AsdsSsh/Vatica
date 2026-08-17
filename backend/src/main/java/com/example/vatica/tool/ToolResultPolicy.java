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
}
