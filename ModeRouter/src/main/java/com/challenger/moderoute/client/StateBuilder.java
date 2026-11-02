package com.challenger.moderoute.client;

import com.challenger.moderoute.entity.OptionSet;

import java.util.Map;

/**
 * 精判输入拼装。卡片正文只放「适用条件」——决策模型有上下文稀释问题，
 * 无关长文本会降低判断质量；其余字段不进精判输入。
 */
public final class StateBuilder {

    private StateBuilder() {
    }

    public static String build(String task, OptionSet options, Map<String, String> descriptions) {
        StringBuilder sb = new StringBuilder(task.length() + 1_024);
        sb.append("任务：\n").append(task.strip()).append("\n\n");
        sb.append("候选推理骨架（唯一判断依据是每个骨架的「适用条件」）：\n");
        for (OptionSet.Option option : options.options()) {
            sb.append(option.letter()).append(". ");
            if (option.isNone()) {
                sb.append(OptionSet.NONE_DESCRIPTION);
            } else {
                sb.append(descriptions.getOrDefault(option.cardId(), ""));
            }
            sb.append('\n');
        }
        sb.append('\n')
                .append("选出最能让模型把这道任务做对的骨架；若没有一个适配，选 ")
                .append(options.noneLetter())
                .append("。只比较适用条件与任务的匹配程度，不要因为任务看起来复杂就默认注入。");
        return sb.toString();
    }
}
