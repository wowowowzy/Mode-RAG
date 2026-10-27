package com.challenger.moderoute.api;

import com.challenger.moderoute.api.dto.HealthResponse;
import com.challenger.moderoute.card.CardLibrary;
import com.challenger.moderoute.card.CardLibraryService;
import com.challenger.moderoute.config.ModeRouteProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "health", description = "健康检查")
public class HealthController {

    private final CardLibraryService libraryService;
    private final ModeRouteProperties properties;

    public HealthController(CardLibraryService libraryService, ModeRouteProperties properties) {
        this.libraryService = libraryService;
        this.properties = properties;
    }

    @GetMapping("/healthz")
    @Operation(summary = "健康检查")
    public HealthResponse health() {
        CardLibrary library = libraryService.library();
        boolean vectorsReady = library.vectorsReady();
        return new HealthResponse(
                vectorsReady ? "UP" : "DEGRADED",
                library.version(),
                library.cards().size(),
                vectorsReady,
                library.embeddingModel(),
                properties.getDecider().getProvider());
    }
}
