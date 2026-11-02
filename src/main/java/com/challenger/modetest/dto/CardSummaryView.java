package com.challenger.modetest.dto;

/**
 * 按域统计的卡片数。null 表示「从未成功读到」，前端显示「—」而不是编造数字。
 */
public record CardSummaryView(
        Integer planning,
        Integer debate,
        Integer general,
        String libraryVersion,
        boolean stale) {
}
