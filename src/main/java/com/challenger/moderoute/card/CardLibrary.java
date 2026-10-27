package com.challenger.moderoute.card;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模式库的不可变快照：版本号 + 卡片 + id 索引 + 与卡片同序等长的向量。
 * 三样东西打包在一起，替换时整体原子替换，避免「卡片 v2、向量 v1」的窗口。
 */
public record CardLibrary(
        String version,
        List<Card> cards,
        Map<String, Card> byId,
        float[][] vectors,
        String embeddingModel) {

    public CardLibrary {
        cards = List.copyOf(cards);
        Map<String, Card> index = new LinkedHashMap<>();
        for (Card card : cards) {
            index.put(card.id(), card);
        }
        byId = Collections.unmodifiableMap(index);
    }

    public static CardLibrary of(String version, List<Card> cards) {
        List<Card> sorted = cards.stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
        return new CardLibrary(version, sorted, Map.of(), new float[0][], "");
    }

    public boolean vectorsReady() {
        return !cards.isEmpty() && vectors.length == cards.size();
    }

    public int dimension() {
        return vectorsReady() ? vectors[0].length : 0;
    }

    public CardLibrary withVectors(float[][] newVectors, String model) {
        float[][] copy = new float[newVectors.length][];
        for (int i = 0; i < newVectors.length; i++) {
            copy[i] = newVectors[i].clone();
        }
        return new CardLibrary(version, cards, byId, copy, model);
    }

    public List<String> cardIds() {
        return cards.stream().map(Card::id).toList();
    }
}
