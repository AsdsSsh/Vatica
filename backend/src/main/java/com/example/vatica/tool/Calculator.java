package com.example.vatica.tool;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 数学表达式求值器（递归下降解析，无第三方依赖）。
 *
 * <p>JDK 21 无内置 JS 引擎（Nashorn 已移除），手写解析器本身也是"有逻辑、可单测、面试可讲"的点。
 * 支持：+ - * / 四则运算、括号、小数、百分号（如 50%*200）、一元负号。
 *
 * <p>误差边界（迭代 2.5 I2.5-4 评审结论：保留 double + 文档化边界）：
 * <ul>
 *   <li>内部用 double 运算（约 15-16 位有效数字），结果四舍五入保留 10 位小数；
 *       0.1+0.2 这类浮点尾巴由格式化消除，适用于办公统计量级（金额/百分比/计数）</li>
 *   <li>金融高精度 / 超大数科学计算不在支持范围，应换 BigDecimal 全链路或专用库</li>
 *   <li>{@link #MAX_LENGTH} 200 字符上限使纯字面量四则运算最大约 1e198，无法溢出 double；
 *       格式化的 NaN/Infinity 检查是防御性兜底</li>
 *   <li>不支持科学计数法（e/E 按非法内容报错，绝不静默截断）；百分号为"字面量 ÷100"语义</li>
 * </ul>
 *
 * <p>错误约定：非法表达式/除零抛 {@link IllegalArgumentException}，message 回传模型继续循环。
 */
public final class Calculator {

    private static final int MAX_LENGTH = 200;

    private final String src;
    private int pos;

    private Calculator(String src) {
        this.src = src;
    }

    /** 计算表达式并返回格式化结果（最多 10 位小数，去尾零）。 */
    public static String calculate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("操作失败：表达式不能为空。");
        }
        if (expression.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("操作失败：表达式超过 " + MAX_LENGTH + " 字符上限。");
        }
        try {
            Calculator parser = new Calculator(expression);
            double result = parser.parseExpression();
            parser.skipWhitespace();
            if (parser.pos < parser.src.length()) {
                throw new IllegalArgumentException("操作失败：表达式包含无法解析的内容（位置 " + parser.pos + "）。");
            }
            return format(result);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("操作失败：" + e.getMessage());
        }
    }

    /** expression := term (('+' | '-') term)* */
    private double parseExpression() {
        double value = parseTerm();
        while (true) {
            skipWhitespace();
            if (peek('+')) {
                pos++;
                value += parseTerm();
            } else if (peek('-')) {
                pos++;
                value -= parseTerm();
            } else {
                return value;
            }
        }
    }

    /** term := factor (('*' | '/') factor)* */
    private double parseTerm() {
        double value = parseFactor();
        while (true) {
            skipWhitespace();
            if (peek('*')) {
                pos++;
                value *= parseFactor();
            } else if (peek('/')) {
                pos++;
                double divisor = parseFactor();
                if (divisor == 0) {
                    throw new ArithmeticException("除数不能为零。");
                }
                value /= divisor;
            } else {
                return value;
            }
        }
    }

    /** factor := '-' factor | '(' expression ')' | number ('%')? */
    private double parseFactor() {
        skipWhitespace();
        if (peek('-')) {
            pos++;
            return -parseFactor();
        }
        if (peek('(')) {
            pos++;
            double value = parseExpression();
            skipWhitespace();
            if (!peek(')')) {
                throw new IllegalArgumentException("操作失败：括号不匹配。");
            }
            pos++;
            return value;
        }
        double value = parseNumber();
        skipWhitespace();
        if (peek('%')) {
            pos++;
            return value / 100;
        }
        return value;
    }

    private double parseNumber() {
        skipWhitespace();
        int start = pos;
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("操作失败：表达式包含无法解析的内容（位置 " + start + "）。");
        }
        try {
            return Double.parseDouble(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("操作失败：数字格式错误。");
        }
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private boolean peek(char c) {
        return pos < src.length() && src.charAt(pos) == c;
    }

    /** 结果格式化：整数输出整数；小数最多保留 10 位并去尾零（避免 0.1+0.2 的浮点尾巴）。 */
    private static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("操作失败：计算结果无效（溢出或除零）。");
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return BigDecimal.valueOf(value)
                .setScale(10, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
