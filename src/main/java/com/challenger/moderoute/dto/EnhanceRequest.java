package com.challenger.moderoute.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "增强请求")
public record EnhanceRequest(
        @Schema(description = "任务文本，1–4000 字", example = "为三条产线排布五张订单的工时，订单 O1 最晚第 2 天完成")
        String task,

        @Schema(description = "可选参数")
        Options options,

        @Schema(description = "调用方链路标识，可选；缺省时由服务生成", example = "trace-001")
        String traceId,

        @Schema(hidden = true)
        OverrideSpec override) {

    public record Options(
            @Schema(description = "粗筛候选数，3–26，默认 8", example = "8")
            Integer topK,

            @Schema(description = "注入文本的 token 预算，默认 400", example = "400")
            Integer budgetTokens) {
    }

    @Schema(hidden = true)
    public record OverrideSpec(
            @Schema(hidden = true) String mode,
            @Schema(hidden = true) String cardId,
            @Schema(hidden = true) Integer seed) {
    }
}
