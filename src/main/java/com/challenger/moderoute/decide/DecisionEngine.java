package com.challenger.moderoute.decide;

import java.util.Map;

/**
 * 精判引擎。实现可以是本地 JEV、远程 JEV 或 mock；
 * 契约只有一条：给出与 options 同序的概率分布。
 */
public interface DecisionEngine {

    String name();

    /**
     * @param task         原始任务文本
     * @param options      选项顺序（A–Z，最后一位是 None）
     * @param descriptions cardId -> applicableWhen，None 的描述由实现自行补
     */
    EngineScores score(String task, OptionSet options, Map<String, String> descriptions);
}
