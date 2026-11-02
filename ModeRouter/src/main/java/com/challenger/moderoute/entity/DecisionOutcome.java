package com.challenger.moderoute.entity;

import com.challenger.moderoute.entity.enums.GateAction;


/**
 * 一次精判的结果：完整概率分布 + 门控动作。
 * 两者一起返回，避免调用方重复计算 argmax。
 */
public record DecisionOutcome(Decision decision, GateAction action, String cardId, double confidence) {
}
