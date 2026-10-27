package com.challenger.moderoute.service;

import com.challenger.moderoute.entity.ScoredCard;

import com.challenger.moderoute.entity.ScoredCard;

import java.util.List;

public interface RecallService {

    /** 粗筛：把任务向量化后取 top-k 卡片。 */
    List<ScoredCard> recall(String task, int topK);
}
