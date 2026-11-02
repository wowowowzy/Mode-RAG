package com.challenger.moderoute.dto;

import com.challenger.moderoute.common.util.LibraryVersion;
import com.challenger.moderoute.mapper.VectorMapper;

public record HealthResponse(
        String status,
        String libraryVersion,
        int cardCount,
        boolean vectorsReady,
        String embeddingModel,
        String vectorStore,
        String decider) {
}
