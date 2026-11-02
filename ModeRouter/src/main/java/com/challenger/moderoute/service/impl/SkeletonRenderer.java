package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.entity.Card;
import org.springframework.stereotype.Component;

/**
 * 把卡片渲染成可直接拼入 prompt 的文本。
 * 不做长度裁剪：注入文本就是卡片全文（含失败模式与输出格式）。
 */
@Component
public class SkeletonRenderer {

    public String render(Card card) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("【推理骨架：").append(card.name()).append("】\n\n");
        sb.append("适用条件：").append(card.applicableWhen()).append("\n\n");

        sb.append("按顺序执行：\n");
        for (int i = 0; i < card.steps().size(); i++) {
            sb.append(i + 1).append(". ").append(card.steps().get(i)).append('\n');
        }

        sb.append("\n常见失败模式（逐条自查）：\n");
        for (String failureMode : card.failureModes()) {
            sb.append("- ").append(failureMode).append('\n');
        }

        sb.append("\n停止条件（满足即停止并说明，不要硬给结论）：\n");
        for (String exitCondition : card.exitConditions()) {
            sb.append("- ").append(exitCondition).append('\n');
        }

        sb.append("\n输出格式：\n").append(card.outputContract()).append('\n');
        if (!card.domain().outputFormatBlock().isBlank()) {
            sb.append(card.domain().outputFormatBlock().strip()).append('\n');
        }
        return sb.toString();
    }
}
