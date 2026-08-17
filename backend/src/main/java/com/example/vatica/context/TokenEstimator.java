package com.example.vatica.context;

/**
 * 迭代 15 I15-8：token 估算器（工程近似）——中文/全角字符约 1 字 ≈ 1 token，
 * 其余字符 4 字符 ≈ 1 token（向上取整）。先用于上下文预算控制，不做精确计费。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        int[] codePoints = text.codePoints().toArray();
        for (int cp : codePoints) {
            if (isCjk(cp)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    public static int estimate(Iterable<String> texts) {
        int total = 0;
        for (String text : texts) {
            total += estimate(text);
        }
        return total;
    }

    private static boolean isCjk(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)   // 日文假名
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)   // 韩文音节
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);  // 全角符号
    }
}
