package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.common.util.TokenEstimator;
import com.challenger.moderoute.entity.Card;

import org.springframework.stereotype.Component;

/**
 * 把卡片渲染成可直接拼入 prompt 的文本。
 * 预算不够时按固定顺序裁剪：先去掉失败模式，再去掉输出格式示例，最后按 token 截断。
 * 裁剪顺序固定，保证同一任务、同一库版本输出逐字节一致。
 */
@Component
public class SkeletonRenderer {

    public String render(Card card, int budgetTokens) {
        String full = compose(card, true, true);
        if (TokenEstimator.estimate(full) <= budgetTokens) {
            return full;
        }
        // 先丢输出格式示例（字段名仍在 outputContract 里），再丢失败模式，最后才硬截断。
        String noSample = compose(card, true, false);
        if (TokenEstimator.estimate(noSample) <= budgetTokens) {
            return noSample;
        }
        String lean = compose(card, false, false);
        if (TokenEstimator.estimate(lean) <= budgetTokens) {
            return lean;
        }
        return TokenEstimator.truncateToTokens(lean, budgetTokens);
    }

    private String compose(Card card, boolean includeFailureModes, boolean includeOutputFormat) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("【推理骨架：").append(card.name()).append("】\n\n");
        sb.append("适用条件：").append(card.applicableWhen()).append("\n\n");

        sb.append("按顺序执行：\n");
        for (int i = 0; i < card.steps().size(); i++) {
            sb.append(i + 1).append(". ").append(card.steps().get(i)).append('\n');
        }

        if (includeFailureModes) {
            sb.append("\n常见失败模式（逐条自查）：\n");
            for (String failureMode : card.failureModes()) {
                sb.append("- ").append(failureMode).append('\n');
            }
        }

        sb.append("\n停止条件（满足即停止并说明，不要硬给结论）：\n");
        for (String exitCondition : card.exitConditions()) {
            sb.append("- ").append(exitCondition).append('\n');
        }

        sb.append("\n输出格式：\n").append(card.outputContract()).append('\n');
        if (includeOutputFormat && !card.domain().outputFormatBlock().isBlank()) {
            sb.append(card.domain().outputFormatBlock().strip()).append('\n');
        }
        return sb.toString();
    }
}
