package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.entity.Decision;
import com.challenger.moderoute.entity.GateDecision;
import com.challenger.moderoute.entity.OptionSet;
import com.challenger.moderoute.entity.enums.GateAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * P2 精判后规则否决：只否决「明确冲突」，不覆盖 JEV 的正常选择。
 *
 * 当前只对有强信号要求的族生效（mutex）：任务文本里没有任何互斥信号，却选中了 mutex 卡，
 * 属于明确冲突，改取概率次高的非冲突候选；若全部冲突则返回 FALLBACK。
 * 其余族不启用否决，避免把精判退化成关键词匹配。
 */
@Component
public class RuleVeto {

    private static final Set<String> VETO_FAMILIES = Set.of("mutex");
    private static final List<String> MUTEX_SIGNALS =
            List.of("互斥", "不能同", "冲突", "同一资源", "同一天", "争用");

    /** 选中卡是否与任务明确冲突。 */
    public boolean conflicts(String task, Card card) {
        if (task == null || card == null || card.family() == null) {
            return false;
        }
        if (!VETO_FAMILIES.contains(card.family())) {
            return false;
        }
        return MUTEX_SIGNALS.stream().noneMatch(task::contains);
    }

    /**
     * 若 gated 结果冲突，则按概率从高到低找第一个非冲突候选；
     * 找不到时返回 FALLBACK（保证「都不合适」仍能兜底）。
     */
    public GateDecision apply(String task, Decision decision, CardLibrary library, GateDecision gated) {
        if (gated.action() != GateAction.INJECT || gated.cardId() == null) {
            return gated;
        }
        Card chosen = library.byId().get(gated.cardId());
        if (!conflicts(task, chosen)) {
            return gated;
        }

        double[] probs = decision.probabilitiesUnsafe();
        List<OptionSet.Option> options = decision.optionList();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < options.size() - 1; i++) {
            order.add(i);
        }
        order.sort((a, b) -> Double.compare(probs[b], probs[a])); // 确定性：同分按索引（即 cardId 字典序）

        for (int index : order) {
            String cardId = options.get(index).cardId();
            if (cardId == null || cardId.equals(gated.cardId())) {
                continue;
            }
            Card candidate = library.byId().get(cardId);
            if (!conflicts(task, candidate)) {
                return new GateDecision(GateAction.INJECT, options.get(index).letter(), cardId, probs[index]);
            }
        }
        OptionSet.Option none = options.get(options.size() - 1);
        return new GateDecision(GateAction.FALLBACK, none.letter(), null, gated.confidence());
    }
}
