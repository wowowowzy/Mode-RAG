package com.challenger.moderoute.dto;

import com.challenger.moderoute.common.util.LibraryVersion;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "增强响应。prompt 永不为 null，未命中时为空串。")
public record EnhanceResponse(
        @Schema(description = "可直接拼入 prompt 的推理骨架；未命中为空串") String prompt,
        @Schema(description = "链路标识") String traceId,
        @Schema(description = "极简决策信息") Meta meta) {

    @Schema(description = "决策元信息")
    public record Meta(
            @Schema(description = "INJECT / FALLBACK / DEGRADED", example = "INJECT") String action,
            @Schema(description = "命中的卡片 id，未命中为 null") String cardId,
            @Schema(description = "标定后置信度 0–1", example = "0.8241") double confidence,
            @Schema(description = "模式库版本") String libraryVersion) {
    }
}
