package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.service.RenderService;
import org.springframework.stereotype.Service;

/**
 * 渲染层实现：把渲染策略收在一个 service 后面。
 */
@Service
public class RenderServiceImpl implements RenderService {

    private final SkeletonRenderer skeletonRenderer;

    public RenderServiceImpl(SkeletonRenderer skeletonRenderer) {
        this.skeletonRenderer = skeletonRenderer;
    }

    @Override
    public String render(Card card, int budgetTokens) {
        return skeletonRenderer.render(card, budgetTokens);
    }
}
