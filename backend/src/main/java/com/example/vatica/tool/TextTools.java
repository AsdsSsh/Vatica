package com.example.vatica.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 计算与文本统计工具（calculator / text_stats）。
 *
 * <p>纯 POJO，无 Spring 依赖；错误约定同 {@link FileTools}：
 * 非法输入抛 {@link IllegalArgumentException}（message 回传模型继续循环）。
 */
public final class TextTools {

    @Tool(name = "calculator", description = "计算数学表达式并返回数值结果。支持 + - * / 四则运算、括号、小数、百分号、负数。"
            + "用于统计汇总、金额与时间差计算等，例如 \"3 + 4 + 5\"、\"(1280 + 540) * 0.9\"、\"800 - 200\"。"
            + "只做数值运算，不解析自然语言。")
    public String calculate(@ToolParam(description = "数学表达式，长度不超过 200 字符，如 \"(3+4)*2\"",
            required = true) String expression) {
        return Calculator.calculate(expression);
    }

    @Tool(name = "text_stats", description = "统计一段文本的中文字数、总字符数（不含空白）、行数、段落数。"
            + "用于周报写作前的数据整理，例如统计某记录文件的行数与段落数。"
            + "只做统计，不修改文本内容。")
    public String textStats(@ToolParam(description = "待统计的文本内容", required = true) String text) {
        if (text == null) {
            text = "";
        }
        if (text.isBlank()) {
            return "中文字数=0, 字符数(不含空白)=0, 行数=0, 段落数=0";
        }
        long chineseChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long nonBlankChars = text.chars()
                .filter(c -> !Character.isWhitespace(c))
                .count();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        long lineCount = normalized.split("\n", -1).length;
        // 段落 = 按空行分隔的文本块
        long paraCount = 0;
        for (String block : normalized.split("\\n\\s*\\n+", -1)) {
            if (!block.isBlank()) {
                paraCount++;
            }
        }
        return "中文字数=" + chineseChars + ", 字符数(不含空白)=" + nonBlankChars
                + ", 行数=" + lineCount + ", 段落数=" + paraCount;
    }
}
