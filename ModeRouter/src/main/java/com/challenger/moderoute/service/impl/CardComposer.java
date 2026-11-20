package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.common.util.TokenEstimator;
import com.challenger.moderoute.entity.Card;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P2 精判后组合（第一阶段：只做域级固定验证节点，不做条件组合）。
 *
 * 不依赖主卡是否正确，因此不会放大选卡错误；只提升输出规范性。
 * 组合后总 token 超过 MAX_TOKENS 时放弃后置块，保证预算目标。
 */
@Component
public class CardComposer {

    private static final int MAX_TOKENS = 800;

    private static final String PLANNING_POST =
            "\n\n【约束自检】逐条核对 capacity / window / mutex / precedence 约束；"
                    + "未满足时明确指出冲突项，不要硬给结论。\n";
    private static final String DEBATE_POST =
            "\n\n【论述自检】先复述对方最强论点，再给出评价；不得稻草人化。\n";

    public record Composed(String prompt, List<String> composedCards, int graphNodes) {
    }

    public Composed compose(Card card, String baseRender) {
        String suffix = switch (card.domain()) {
            case PLANNING -> PLANNING_POST;
            case DEBATE -> DEBATE_POST;
            default -> "";
        };
        String prompt = baseRender;
        if (!suffix.isBlank() && TokenEstimator.estimate(baseRender + suffix) <= MAX_TOKENS) {
            prompt = baseRender + suffix;
        }
        return new Composed(prompt, List.of(card.id()), card.relations().nodeCount());
    }
}
