package com.challenger.modetest.dto;

import com.challenger.modetest.domain.Task;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record TaskView(
        String taskId,
        String domain,
        String prompt,
        List<JsonNode> constraints,
        List<JsonNode> stances,
        List<String> acceptableCards,
        String split,
        String family,
        JsonNode meta) {

    public static TaskView of(Task task) {
        return new TaskView(task.taskId(), task.domain(), task.prompt(), task.constraints(),
                task.stances(), task.acceptableCards(), task.split(), task.family(), task.meta());
    }
}
