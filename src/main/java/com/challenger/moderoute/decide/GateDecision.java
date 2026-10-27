package com.challenger.moderoute.decide;

public record GateDecision(GateAction action, char letter, String cardId, double confidence) {
}
