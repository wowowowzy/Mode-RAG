package com.challenger.moderoute.decide;

/**
 * 门控。「回退」是候选池里一个可被选中的选项，不是「概率太低」的事后推断：
 * 决策模型必然从封闭集里挑一个，两张烂卡 55/45 纠结时它也会给出一张「中置信」的卡，
 * 所以必须让「都不合适」能被投出来。
 */
public final class Gate {

    private Gate() {
    }

    public static GateDecision decide(Decision decision, double injectFloor) {
        int best = decision.argmaxIndex();
        OptionSet.Option option = decision.optionList().get(best);
        double confidence = decision.probabilitiesUnsafe()[best];

        if (option.isNone()) {
            return new GateDecision(GateAction.FALLBACK, option.letter(), null, confidence);
        }
        if (injectFloor > 0 && confidence < injectFloor) {
            return new GateDecision(GateAction.FALLBACK, option.letter(), null, confidence);
        }
        return new GateDecision(GateAction.INJECT, option.letter(), option.cardId(), confidence);
    }
}
