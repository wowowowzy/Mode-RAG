package com.challenger.moderoute.controller;

import com.challenger.moderoute.dto.EnhanceRequest;
import com.challenger.moderoute.dto.EnhanceResponse;
import com.challenger.moderoute.service.EnhanceService;

import com.challenger.moderoute.dto.EnhanceRequest;
import com.challenger.moderoute.dto.EnhanceResponse;
import com.challenger.moderoute.service.EnhanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@Tag(name = "enhance", description = "推理骨架召回")
public class EnhanceController {

    private final EnhanceService enhanceService;

    public EnhanceController(EnhanceService enhanceService) {
        this.enhanceService = enhanceService;
    }

    @PostMapping(value = "/enhance", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "召回并渲染推理骨架",
            description = "成功恒为 200；prompt 永不为 null，未命中时为空串；降级通过 meta.action 可观测。")
    public EnhanceResponse enhance(@RequestBody(required = false) EnhanceRequest request) {
        return enhanceService.enhance(request);
    }
}
