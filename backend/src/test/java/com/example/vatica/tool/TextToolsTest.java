package com.example.vatica.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 文本统计工具单测：中文统计、行/段落切分、边界输入。 */
class TextToolsTest {

    private final TextTools textTools = new TextTools();

    /** 中文文本统计：中文字数、行数（含空行）、段落数 */
    @Test
    void countsChineseText() {
        String result = textTools.textStats("第一行\n第二行内容\n\n第三段");
        // 中文字数：第 一 行 第 二 行 内 容 第 三 段 = 11
        assertThat(result).contains("中文字数=11");
        assertThat(result).contains("行数=4");   // split("\n", -1)：含中间空行共 4 行
        assertThat(result).contains("段落数=2"); // 空行分隔出 2 段
    }

    /** 空串与纯空白全零（含 null） */
    @Test
    void emptyAndBlankText_returnsZero() {
        assertThat(textTools.textStats("")).isEqualTo("中文字数=0, 字符数(不含空白)=0, 行数=0, 段落数=0");
        assertThat(textTools.textStats("   \n  ")).isEqualTo("中文字数=0, 字符数(不含空白)=0, 行数=0, 段落数=0");
        assertThat(textTools.textStats(null)).isEqualTo("中文字数=0, 字符数(不含空白)=0, 行数=0, 段落数=0");
    }

    /** 中英数字混合统计 */
    @Test
    void countsMixedChineseEnglishDigits() {
        String result = textTools.textStats("Hello 世界 123");
        // 中文字数：世 界 = 2；字符数（不含空白）：5+2+3=10
        assertThat(result).contains("中文字数=2");
        assertThat(result).contains("字符数(不含空白)=10");
        assertThat(result).contains("行数=1");
        assertThat(result).contains("段落数=1");
    }

    /** 返回格式为键值对 */
    @Test
    void returnsKeyValueFormat() {
        String result = textTools.textStats("测试");
        assertThat(result).matches("中文字数=\\d+, 字符数\\(不含空白\\)=\\d+, 行数=\\d+, 段落数=\\d+");
    }
}
