package com.challenger.moderoute.service;

import com.challenger.moderoute.entity.Card;

public interface RenderService {

    /** 把卡片渲染成可直接拼入 prompt 的文本，受 token 预算约束。 */
    String render(Card card, int budgetTokens);
}
