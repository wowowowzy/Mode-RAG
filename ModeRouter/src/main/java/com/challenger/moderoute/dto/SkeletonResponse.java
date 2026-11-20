package com.challenger.moderoute.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 指定卡片的完整骨架。只读排查端点，供三臂实验的 oracle 臂使用。 */
public record SkeletonResponse(
        @Schema(description = "卡片 id") String cardId,
        @Schema(description = "卡片名称") String name,
        @Schema(description = "所属域") String domain,
        @Schema(description = "可直接拼入 system 段的骨架文本") String prompt) {
}
