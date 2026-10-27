package com.challenger.moderoute.service;

import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.enums.CardDomain;


public interface RenderService {

    /** 把卡片渲染成可直接拼入 prompt 的文本，受 token 预算约束。 */
    String render(Card card, int budgetTokens);

    /** placebo 臂：同结构、内容通用、长度对齐的假骨架。 */
    String placebo(CardDomain domain, int targetChars);
}
