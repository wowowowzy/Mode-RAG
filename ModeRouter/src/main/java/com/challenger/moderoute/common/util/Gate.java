package com.challenger.moderoute.common.util;

import com.challenger.moderoute.entity.Decision;
import com.challenger.moderoute.entity.enums.GateAction;
import com.challenger.moderoute.entity.GateDecision;
import com.challenger.moderoute.entity.OptionSet;

/**
 * 门控。「回退」是候选池里一个可被选中的选项，不是「概率太低」的事后推断：
 * 决策模型必然从封闭集里挑一个，两张烂卡 55/45 纠结时它也会给出一张「中置信」的卡，
 * 所以必须让「都不合适」能被投出来。
 */
public final class Gate {

    private Gate() {
    }

    public static GateDecision decide(Decision decision, double injectFloor) {
        return decide(decision, injectFloor, 0.0);
    }

    public static GateDecision decide(Decision decision, double injectFloor, double noneMargin) {
        double[] probs = decision.probabilitiesUnsafe();
        java.util.List<OptionSet.Option> opts = decision.optionList();
        int noneIndex = opts.size() - 1;

        int best = decision.argmaxIndex();           // 含"平票优先 None"语义
        // None margin：None 虽为 argmax，但未显著领先最佳真实卡时，改为注入该卡
        if (opts.get(best).isNone() && noneMargin > 0) {
            int bestCard = -1;
            for (int i = 0; i < noneIndex; i++) {
                if (bestCard < 0 || probs[i] > probs[bestCard]) {
                    bestCard = i;
                }
            }
            if (bestCard >= 0 && probs[noneIndex] < probs[bestCard] + noneMargin) {
                best = bestCard;
            }
        }

        OptionSet.Option option = opts.get(best);
        double confidence = probs[best];
        if (option.isNone()) {
            return new GateDecision(GateAction.FALLBACK, option.letter(), null, confidence);
        }
        if (injectFloor > 0 && confidence < injectFloor) {
            return new GateDecision(GateAction.FALLBACK, option.letter(), null, confidence);
        }
        return new GateDecision(GateAction.INJECT, option.letter(), option.cardId(), confidence);
    }
}
