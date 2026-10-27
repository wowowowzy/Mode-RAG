package com.challenger.moderoute.card;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模式库的不可变快照：版本号 + 卡片 + id 索引。
 * 向量不放在这里——它由 VectorStore 持有，按 (库版本, 模型) 分域管理。
 */
public record CardLibrary(String version, List<Card> cards, Map<String, Card> byId) {

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
        return new CardLibrary(version, sorted, Map.of());
    }

    public List<String> cardIds() {
        return cards.stream().map(Card::id).toList();
    }
}
