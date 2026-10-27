package com.challenger.moderoute.controller;

import com.challenger.moderoute.dto.CardsResponse;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.service.CardLibraryService;

import com.challenger.moderoute.dto.CardsResponse;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.service.CardLibraryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@Tag(name = "cards", description = "模式库排查接口")
public class CardsController {

    private final CardLibraryService libraryService;

    public CardsController(CardLibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @GetMapping("/cards")
    @Operation(summary = "列出模式库", description = "用于确认加载了几张卡、库版本是否正确；不承担 UI 职责。")
    public CardsResponse cards() {
        CardLibrary library = libraryService.library();
        return CardsResponse.of(library.version(), library.cards());
    }
}
