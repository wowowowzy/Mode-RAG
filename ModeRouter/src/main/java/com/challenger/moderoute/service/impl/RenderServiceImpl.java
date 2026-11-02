package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.service.RenderService;
import org.springframework.stereotype.Service;

/**
 * 渲染层实现：完整输出卡片内容，不按 token 预算裁剪。
 */
@Service
public class RenderServiceImpl implements RenderService {

    private final SkeletonRenderer skeletonRenderer;

    public RenderServiceImpl(SkeletonRenderer skeletonRenderer) {
        this.skeletonRenderer = skeletonRenderer;
    }

    @Override
    public String render(Card card) {
        return skeletonRenderer.render(card);
    }
}
