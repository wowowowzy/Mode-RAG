package com.challenger.moderoute.controller;

import com.challenger.moderoute.common.ApiException;
import com.challenger.moderoute.dto.CardsResponse;
import com.challenger.moderoute.dto.SkeletonResponse;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.service.CardLibraryService;
import com.challenger.moderoute.service.RenderService;
import com.challenger.moderoute.service.impl.CardComposer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@Tag(name = "cards", description = "模式库排查接口")
public class CardsController {

    private final CardLibraryService libraryService;
    private final RenderService renderService;
    private final CardComposer cardComposer;

    public CardsController(CardLibraryService libraryService,
                           RenderService renderService,
                           CardComposer cardComposer) {
        this.libraryService = libraryService;
        this.renderService = renderService;
        this.cardComposer = cardComposer;
    }

    @GetMapping("/cards")
    @Operation(summary = "列出模式库", description = "用于确认加载了几张卡、库版本是否正确；不承担 UI 职责。")
    public CardsResponse cards() {
        CardLibrary library = libraryService.library();
        return CardsResponse.of(library.version(), library.cards());
    }

    /**
     * 只读：返回指定卡片的完整骨架。不改变 /v1/enhance 的「不接受指定卡片」纪律——
     * 这是排查与对照实验的独立端点，不参与正常决策链路。
     */
    @GetMapping("/cards/{cardId}/skeleton")
    @Operation(summary = "取指定卡片的骨架", description = "只读；供三臂实验的 oracle 臂使用。")
    public SkeletonResponse skeleton(@PathVariable String cardId) {
        Card card = libraryService.library().byId().get(cardId);
        if (card == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "卡片不存在: " + cardId);
        }
        String prompt = cardComposer.compose(card, renderService.render(card)).prompt();
        return new SkeletonResponse(card.id(), card.name(), card.domain().key(), prompt);
    }
}
