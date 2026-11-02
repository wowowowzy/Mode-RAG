package com.challenger.moderoute.service;

import com.challenger.moderoute.entity.Card;

public interface RenderService {

    /** 把卡片完整渲染成可直接拼入 prompt 的文本，不做长度裁剪。 */
    String render(Card card);
}
