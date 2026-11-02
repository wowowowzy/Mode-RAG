package com.challenger.moderoute.entity;

import com.challenger.moderoute.entity.enums.GateAction;

public record GateDecision(GateAction action, char letter, String cardId, double confidence) {
}
