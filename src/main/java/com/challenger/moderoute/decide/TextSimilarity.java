package com.challenger.moderoute.decide;

import java.util.HashSet;
import java.util.Set;

/**
 * 字符二元组余弦，只用于规则降级：没有向量、也不该有外部调用。
 */
public final class TextSimilarity {

    private TextSimilarity() {
    }

    public static double bigramCosine(String a, String b) {
        Set<Integer> left = bigrams(a);
        Set<Integer> right = bigrams(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Set<Integer> smaller = left.size() <= right.size() ? left : right;
        Set<Integer> larger = smaller == left ? right : left;
        int intersection = 0;
        for (Integer gram : smaller) {
            if (larger.contains(gram)) {
                intersection++;
            }
        }
        return intersection / (Math.sqrt(left.size()) * Math.sqrt(right.size()));
    }

    private static Set<Integer> bigrams(String text) {
        Set<Integer> grams = new HashSet<>();
        if (text == null) {
            return grams;
        }
        StringBuilder filtered = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                filtered.appendCodePoint(Character.toLowerCase(cp));
            }
            i += Character.charCount(cp);
        }
        String value = filtered.toString();
        if (value.isEmpty()) {
            return grams;
        }
        if (value.length() == 1) {
            grams.add(value.codePointAt(0));
            return grams;
        }
        for (int i = 0; i + 1 < value.length(); i++) {
            grams.add((value.charAt(i) << 16) | value.charAt(i + 1));
        }
        return grams;
    }
}
