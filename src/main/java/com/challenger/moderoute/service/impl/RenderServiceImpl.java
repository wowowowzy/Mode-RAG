package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.enums.CardDomain;
import com.challenger.moderoute.service.RenderService;

import org.springframework.stereotype.Service;

/**
 * 渲染层实现：把两种渲染策略收在一个 service 后面。
 */
@Service
public class RenderServiceImpl implements RenderService {

    private final SkeletonRenderer skeletonRenderer;
    private final PlaceboRenderer placeboRenderer;

    public RenderServiceImpl(SkeletonRenderer skeletonRenderer, PlaceboRenderer placeboRenderer) {
        this.skeletonRenderer = skeletonRenderer;
        this.placeboRenderer = placeboRenderer;
    }

    @Override
    public String render(Card card, int budgetTokens) {
        return skeletonRenderer.render(card, budgetTokens);
    }

    @Override
    public String placebo(CardDomain domain, int targetChars) {
        return placeboRenderer.render(domain, targetChars);
    }
}
