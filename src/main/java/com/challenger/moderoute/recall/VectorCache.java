package com.challenger.moderoute.recall;

import com.challenger.moderoute.config.ModeRouteProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 向量缓存。纯缓存：缺失、损坏、版本不符都静默重算，删掉后系统行为不变。
 */
@Component
public class VectorCache {

    private static final Logger log = LoggerFactory.getLogger(VectorCache.class);

    private final Path dir;
    private final ObjectMapper mapper;

    public VectorCache(ModeRouteProperties properties, ObjectMapper mapper) {
        this.dir = Path.of(properties.getRecall().getCacheDir()).toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    public Optional<float[][]> load(String libraryVersion, String model, List<String> cardIds) {
        Path file = fileFor(libraryVersion, model, cardIds.size());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!libraryVersion.equals(root.path("version").asText())
                    || !model.equals(root.path("model").asText())) {
                return Optional.empty();
            }
            JsonNode ids = root.path("cardIds");
            if (ids.size() != cardIds.size()) {
                return Optional.empty();
            }
            for (int i = 0; i < cardIds.size(); i++) {
                if (!cardIds.get(i).equals(ids.get(i).asText())) {
                    return Optional.empty();
                }
            }
            JsonNode vectorsNode = root.path("vectors");
            if (vectorsNode.size() != cardIds.size()) {
                return Optional.empty();
            }
            float[][] vectors = new float[vectorsNode.size()][];
            int dimension = -1;
            for (int i = 0; i < vectorsNode.size(); i++) {
                JsonNode row = vectorsNode.get(i);
                float[] values = new float[row.size()];
                for (int j = 0; j < row.size(); j++) {
                    values[j] = (float) row.get(j).asDouble();
                }
                if (values.length == 0) {
                    return Optional.empty();
                }
                if (dimension < 0) {
                    dimension = values.length;
                } else if (dimension != values.length) {
                    return Optional.empty();
                }
                vectors[i] = values;
            }
            return Optional.of(vectors);
        } catch (IOException | RuntimeException e) {
            log.warn("向量缓存不可用，将重新计算: {} ({})", file, e.getMessage());
            return Optional.empty();
        }
    }

    public void save(String libraryVersion, String model, List<String> cardIds, float[][] vectors) {
        try {
            Files.createDirectories(dir);
            var root = mapper.createObjectNode();
            root.put("version", libraryVersion);
            root.put("model", model);
            root.put("dimension", vectors.length == 0 ? 0 : vectors[0].length);
            var ids = root.putArray("cardIds");
            cardIds.forEach(ids::add);
            var rows = root.putArray("vectors");
            for (float[] vector : vectors) {
                var row = rows.addArray();
                for (float value : vector) {
                    row.add(value);
                }
            }
            Path file = fileFor(libraryVersion, model, cardIds.size());
            Path tmp = dir.resolve(file.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.warn("写向量缓存失败（不影响服务）: {}", e.getMessage());
        }
    }

    private Path fileFor(String libraryVersion, String model, int count) {
        return dir.resolve(libraryVersion + "-" + sanitize(model) + "-" + count + ".json");
    }

    private static String sanitize(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }
}
