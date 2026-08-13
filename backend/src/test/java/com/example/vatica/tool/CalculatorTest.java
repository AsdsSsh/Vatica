package com.example.vatica.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 递归下降求值器单测：优先级、括号、小数、负号、百分号、除零与非法输入。 */
class CalculatorTest {

    /** 运算符优先级与括号 */
    @Test
    void respectsPrecedenceAndParentheses() {
        assertThat(Calculator.calculate("1+2*3")).isEqualTo("7");
        assertThat(Calculator.calculate("(1+2)*3")).isEqualTo("9");
        assertThat(Calculator.calculate("10/4")).isEqualTo("2.5");
        assertThat(Calculator.calculate("2*3+4*5")).isEqualTo("26");
    }

    /** 小数与浮点误差（0.1+0.2 必须等于 0.3，无尾差） */
    @Test
    void handlesDecimalsAndFloatPrecision() {
        assertThat(Calculator.calculate("0.5*0.2")).isEqualTo("0.1");
        assertThat(Calculator.calculate("0.1+0.2")).isEqualTo("0.3");
        assertThat(Calculator.calculate("1.5+2.25")).isEqualTo("3.75");
    }

    /** 一元负号 */
    @Test
    void handlesUnaryMinus() {
        assertThat(Calculator.calculate("-3+5")).isEqualTo("2");
        assertThat(Calculator.calculate("2*-3")).isEqualTo("-6");
        assertThat(Calculator.calculate("-(1+2)")).isEqualTo("-3");
    }

    /** 百分号 */
    @Test
    void handlesPercent() {
        assertThat(Calculator.calculate("50%*200")).isEqualTo("100");
        assertThat(Calculator.calculate("800-800*10%")).isEqualTo("720");
    }

    /** 空白容忍 */
    @Test
    void toleratesWhitespace() {
        assertThat(Calculator.calculate(" 3 + 4 + 5 ")).isEqualTo("12");
    }

    /** 除零报错 */
    @Test
    void divisionByZero_throws() {
        assertThatThrownBy(() -> Calculator.calculate("1/0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("除数");
    }

    /** 非法表达式报错（运算符错位/非数字/括号不闭合/空/null） */
    @Test
    void invalidExpressions_throw() {
        assertThatThrownBy(() -> Calculator.calculate("1+*2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Calculator.calculate("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Calculator.calculate("(1+2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Calculator.calculate("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Calculator.calculate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 超长表达式（>200 字符）被拒绝 */
    @Test
    void overlongExpression_rejected() {
        String longExpr = "1+".repeat(101); // 202 字符
        assertThatThrownBy(() -> Calculator.calculate(longExpr))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上限");
    }

    // ===== 迭代 2.5 精度加固（I2.5-4）补充边界用例 =====

    /** 长小数：1/3 四舍五入保留 10 位小数 */
    @Test
    void longDecimal_roundsToTenPlaces() {
        assertThat(Calculator.calculate("1/3")).isEqualTo("0.3333333333");
        assertThat(Calculator.calculate("2/3")).isEqualTo("0.6666666667");
    }

    /** 畸形小数（多个小数点）报"数字格式错误"而非静默截断 */
    @Test
    void malformedDecimals_rejected() {
        assertThatThrownBy(() -> Calculator.calculate("1.2.3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数字格式错误");
        assertThatThrownBy(() -> Calculator.calculate("2..5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数字格式错误");
    }

    /** 科学计数法不支持：明确报错（而非把 1e5 静默算成 1），误差边界见 Calculator 类注释 */
    @Test
    void scientificNotation_rejectedExplicitly() {
        assertThatThrownBy(() -> Calculator.calculate("1e5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法解析");
    }

    /** 相邻两个数字（缺少运算符）被拒绝 */
    @Test
    void adjacentNumbers_rejected() {
        assertThatThrownBy(() -> Calculator.calculate("3 4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法解析");
    }

    /** 连续一元负号：--5 等于 5 */
    @Test
    void doubleUnaryMinus() {
        assertThat(Calculator.calculate("--5")).isEqualTo("5");
        assertThat(Calculator.calculate("- -3")).isEqualTo("3");
    }

    /** 极限长度合法表达式仍返回有限结果：200 字符上限使字面量/四则运算无法溢出 double，
     *  format() 的 Infinity 检查是防御性兜底（见 Calculator 类注释的误差边界说明） */
    @Test
    void maxLengthProductStaysFinite() {
        String big = "9".repeat(99); // 99 位整数
        String result = Calculator.calculate(big + "*" + big);
        assertThat(result).doesNotContain("E").doesNotContain("Infinity");
        assertThat(result).hasSize(198); // 99+99 位乘积
    }
}
