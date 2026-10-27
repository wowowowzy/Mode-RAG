package com.challenger.moderoute.api.dto;

import com.challenger.moderoute.card.Card;

import java.util.List;

public record CardsResponse(String libraryVersion, int count, List<CardView> cards) {

    public record CardView(String id, String domain, String name, String applicableWhen) {

        public static CardView of(Card card) {
            return new CardView(card.id(), card.domain().key(), card.name(), card.applicableWhen());
        }
    }

    public static CardsResponse of(String libraryVersion, List<Card> cards) {
        List<CardView> views = cards.stream().map(CardView::of).toList();
        return new CardsResponse(libraryVersion, views.size(), views);
    }
}
