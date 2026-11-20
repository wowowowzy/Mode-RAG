package com.challenger.moderoute.service;

import com.challenger.moderoute.entity.ScoredCard;


import java.util.List;

public interface RecallService {

    /** 粗筛：把任务向量化后取 top-k 卡片，不做域过滤。 */
    List<ScoredCard> recall(String task, int topK);

    /**
     * 粗筛并按域约束召回范围。domain 为 null / 空时行为与 {@link #recall(String, int)} 一致。
     * 仅允许 planning / debate / general，非法值由上层校验后返回 400。
     */
    List<ScoredCard> recall(String task, int topK, String domain);
}
