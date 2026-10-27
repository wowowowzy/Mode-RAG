package com.challenger.moderoute.common.util;

/**
 * 极简 token 估算：中日韩字符按 1 token，连续西文字母数字按 4 字符 1 token，
 * 其余每个非空白符号按 1 token。不用来精确计费，只用来执行 budgetTokens 上限。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        int asciiRun = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                tokens += flush(asciiRun) + 1;
                asciiRun = 0;
            } else if (cp < 128 && Character.isLetterOrDigit(cp)) {
                asciiRun++;
            } else if (Character.isWhitespace(cp)) {
                tokens += flush(asciiRun);
                asciiRun = 0;
            } else {
                tokens += flush(asciiRun) + 1;
                asciiRun = 0;
            }
        }
        return tokens + flush(asciiRun);
    }

    public static String truncateToTokens(String text, int budget) {
        if (budget <= 0) {
            return "";
        }
        int tokens = 0;
        int asciiRun = 0;
        int lastBreak = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int width = Character.charCount(cp);
            int delta;
            if (isCjk(cp)) {
                delta = flush(asciiRun) + 1;
                asciiRun = 0;
            } else if (cp < 128 && Character.isLetterOrDigit(cp)) {
                asciiRun++;
                delta = 0;
            } else if (Character.isWhitespace(cp)) {
                delta = flush(asciiRun);
                asciiRun = 0;
            } else {
                delta = flush(asciiRun) + 1;
                asciiRun = 0;
            }
            if (tokens + delta > budget) {
                String head = lastBreak > 0 ? text.substring(0, lastBreak) : text.substring(0, i);
                return head.stripTrailing();
            }
            tokens += delta;
            i += width;
            if (cp == '\n') {
                lastBreak = i;
            }
        }
        return text;
    }

    private static int flush(int asciiRun) {
        return asciiRun == 0 ? 0 : (asciiRun + 3) / 4;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x3000 && cp <= 0x303F)
                || (cp >= 0xFF00 && cp <= 0xFFEF);
    }
}
