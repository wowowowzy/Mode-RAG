package com.challenger.modetest.service;

import com.challenger.modetest.client.ModeRouterClient;
import com.challenger.modetest.dto.CardSummaryView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首页卡片计数。带 60 秒缓存，ModeRouter 离线时返回上一次快照（标记 stale），
 * 从未成功过则返回 null —— 前端显示「—」，绝不编造数字。
 */
@Service
public class CardSummaryService {

    private static final Logger log = LoggerFactory.getLogger(CardSummaryService.class);
    private static final long TTL_MS = 60_000L;

    private final ModeRouterClient routerClient;

    private volatile CardSummaryView snapshot;
    private volatile long fetchedAt;

    public CardSummaryService(ModeRouterClient routerClient) {
        this.routerClient = routerClient;
    }

    public CardSummaryView summary() {
        long now = System.currentTimeMillis();
        CardSummaryView current = snapshot;
        if (current != null && now - fetchedAt < TTL_MS) {
            return current;
        }
        try {
            ModeRouterClient.CardsPayload payload = routerClient.cards();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (ModeRouterClient.CardEntry entry : payload.cards()) {
                counts.merge(entry.domain(), 1, Integer::sum);
            }
            CardSummaryView fresh = new CardSummaryView(
                    counts.get("planning"), counts.get("debate"), counts.get("general"),
                    payload.libraryVersion(), false);
            snapshot = fresh;
            fetchedAt = now;
            return fresh;
        } catch (RuntimeException e) {
            log.warn("读取卡片清单失败: {}", e.getMessage());
            return current == null
                    ? new CardSummaryView(null, null, null, null, true)
                    : new CardSummaryView(current.planning(), current.debate(), current.general(),
                            current.libraryVersion(), true);
        }
    }
}
