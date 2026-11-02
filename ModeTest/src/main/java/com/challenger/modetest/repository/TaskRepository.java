package com.challenger.modetest.repository;

import com.challenger.modetest.config.ModeTestProperties;
import com.challenger.modetest.domain.Task;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务集读取。文件是评测输入，启动时一次性读入内存，运行期不重复读盘。
 */
@Repository
public class TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(TaskRepository.class);

    private final Map<String, Task> byId = new LinkedHashMap<>();

    public TaskRepository(ModeTestProperties properties, ObjectMapper mapper) {
        Path file = Path.of(properties.getTasksFile()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("任务集不存在: " + file);
        }
        try {
            List<String> duplicates = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = mapper.readTree(line);
                Task task = new Task(
                        node.path("taskId").asText(),
                        node.path("domain").asText(),
                        node.path("prompt").asText(),
                        toList(node.path("constraints")),
                        toList(node.path("stances")),
                        toStringList(node.path("acceptableCards")),
                        node.path("meta"));
                if (task.taskId().isBlank()) {
                    throw new IllegalStateException("任务缺少 taskId: " + file);
                }
                // 与 CardLoader 保持一致：重复标识是硬错误，不静默覆盖
                if (byId.putIfAbsent(task.taskId(), task) != null) {
                    duplicates.add(task.taskId());
                }
            }
            if (!duplicates.isEmpty()) {
                throw new IllegalStateException(
                        "任务集存在重复 taskId（" + duplicates.size() + " 处）: " + String.join(", ", duplicates));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取任务集失败: " + e.getMessage(), e);
        }
        log.info("任务集已加载: {} 条 ({})", byId.size(), file);
    }

    public List<Task> all() {
        return List.copyOf(byId.values());
    }

    public List<Task> byDomain(String domain) {
        return byId.values().stream().filter(task -> task.domain().equals(domain)).toList();
    }

    public Optional<Task> find(String taskId) {
        return Optional.ofNullable(byId.get(taskId));
    }

    public long count(String domain) {
        return byId.values().stream().filter(task -> task.domain().equals(domain)).count();
    }

    private static List<JsonNode> toList(JsonNode node) {
        List<JsonNode> items = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(items::add);
        }
        return List.copyOf(items);
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> items = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> items.add(item.asText()));
        }
        return List.copyOf(items);
    }
}
