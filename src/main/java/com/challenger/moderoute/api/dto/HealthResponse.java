package com.challenger.moderoute.api.dto;

public record HealthResponse(
        String status,
        String libraryVersion,
        int cardCount,
        boolean vectorsReady,
        String embeddingModel,
        String decider) {
}
