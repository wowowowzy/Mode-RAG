package com.challenger.modetest.judge;

import com.challenger.modetest.domain.Task;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanJudgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Task task(JsonNode... constraints) throws Exception {
        JsonNode meta = MAPPER.readTree("""
                {"family":"capacity-only","split":"test","difficulty":"easy",
                 "demands":[{"order":"K1","hours":4},{"order":"K2","hours":4}]}
                """);
        return new Task("plan-test", "planning", "把 K1、K2 排进产线 A",
                List.of(constraints), List.of(), List.of("planning-capacity-first"), meta);
    }

    private static JsonNode capacity(String line, double perDayHours) throws Exception {
        return MAPPER.readTree("{\"cid\":\"C1\",\"type\":\"capacity\",\"text\":\"产线 A 每天最多 8 小时\","
                + "\"params\":{\"line\":\"" + line + "\",\"perDayHours\":" + perDayHours + "}}");
    }

    @Test
    void legalAssignmentsPassAllConstraints() throws Exception {
        String output = """
                这里是排产结果：
                ```json
                {"assignments":[
                  {"order":"K1","line":"A","day":1,"startHour":0,"hours":4},
                  {"order":"K2","line":"A","day":2,"startHour":0,"hours":4}
                ],"verification":[]}
                ```
                """;
        PlanJudge.Result result = PlanJudge.judge(task(capacity("A", 8)), output);

        assertTrue(result.parsed(), "应能解析 assignments");
        assertEquals(2, result.total(), "1 条约束 + demands 完整性");
        assertEquals(2, result.passed(), "合法排产应全部通过");
        assertTrue(result.failures().isEmpty(), "不应有失败项");
        assertEquals(1.0, result.score(), 1e-9);
    }

    @Test
    void capacityViolationIsReported() throws Exception {
        String output = """
                {"assignments":[
                  {"order":"K1","line":"A","day":1,"startHour":0,"hours":4},
                  {"order":"K2","line":"A","day":1,"startHour":4,"hours":4}
                ]}
                """;
        // 8 小时 > 每日上限 6 小时：demands 满足、capacity 违规
        PlanJudge.Result result = PlanJudge.judge(task(capacity("A", 6)), output);

        assertTrue(result.parsed());
        assertEquals(1, result.passed(), "demands 通过、capacity 失败");
        assertFalse(result.failures().isEmpty(), "应报告 capacity 违规");
        assertTrue(result.failures().get(0).startsWith("capacity 约束未满足"));
    }
}
