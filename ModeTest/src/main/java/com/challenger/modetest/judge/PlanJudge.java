package com.challenger.modetest.judge;

import com.challenger.modetest.domain.Task;
import com.challenger.modetest.judge.PlanExtractor.Assignment;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** planning 任务判定器：核验 assignments 是否满足全部 constraints 与 meta.demands。 */
public final class PlanJudge {

    public record Result(boolean parsed, int passed, int total, List<String> failures) {
        public double score() { return total == 0 ? 0 : (double) passed / total; }
    }

    private PlanJudge() {}

    public static Result judge(Task task, String output) {
        List<JsonNode> constraints = task.constraints() == null ? List.of() : task.constraints();
        int total = constraints.size() + 1; // +1 = demands 完整性
        List<String> failures = new ArrayList<>();

        Optional<List<Assignment>> parsed = PlanExtractor.extract(output);
        if (parsed.isEmpty()) {
            return new Result(false, 0, total, List.of("无法解析 assignments（模型未输出合法 plan_v1 JSON）"));
        }
        List<Assignment> asg = parsed.get();

        // 1) demands 完整性：每单恰好一次、工时正确
        Map<String, Double> demand = demands(task);
        Map<String, Integer> count = new HashMap<>();
        for (Assignment a : asg) {
            count.merge(a.order(), 1, Integer::sum);
            Double want = demand.get(a.order());
            if (want == null) {
                failures.add("demands: 出现未定义订单 " + a.order());
            } else if (Math.abs(a.hours() - want) > 1e-6) {
                failures.add("demands: 订单 " + a.order() + " 工时 " + a.hours() + " ≠ 需求 " + want);
            }
        }
        for (String order : demand.keySet()) {
            int c = count.getOrDefault(order, 0);
            if (c != 1) failures.add("demands: 订单 " + order + " 应恰好排 1 次，实际 " + c + " 次");
        }
        int passed = failures.isEmpty() ? 1 : 0;

        // 2) 逐条 constraint
        for (JsonNode c : constraints) {
            String type = c.path("type").asText("");
            JsonNode p = c.path("params");
            boolean ok = switch (type) {
                case "capacity" -> checkCapacity(asg, p);
                case "window" -> checkWindow(asg, p);
                case "mutex" -> checkMutex(asg, p);
                case "precedence" -> checkPrecedence(asg, p);
                case "ratio" -> checkRatio(asg, p);
                case "assignment" -> checkAssignment(asg, p);
                default -> true;
            };
            if (ok) passed++;
            else failures.add(type + " 约束未满足: " + c.path("text").asText(""));
        }
        return new Result(true, passed, total, failures);
    }

    private static Map<String, Double> demands(Task task) {
        Map<String, Double> m = new HashMap<>();
        JsonNode meta = task.meta();
        if (meta != null && meta.path("demands").isArray()) {
            for (JsonNode d : meta.path("demands")) {
                m.put(d.path("order").asText(""), d.path("hours").asDouble(0));
            }
        }
        return m;
    }

    private static Assignment find(List<Assignment> asg, String order) {
        return asg.stream().filter(a -> a.order().equals(order)).findFirst().orElse(null);
    }

    /** 每台资源每天累计工时 ≤ perDayHours。 */
    private static boolean checkCapacity(List<Assignment> asg, JsonNode p) {
        String line = p.path("line").asText("");
        double cap = p.path("perDayHours").asDouble(Double.MAX_VALUE);
        Map<Integer, Double> perDay = new HashMap<>();
        for (Assignment a : asg) {
            if (a.line().equals(line)) perDay.merge(a.day(), a.hours(), Double::sum);
        }
        return perDay.values().stream().allMatch(v -> v <= cap + 1e-6);
    }

    /** 订单落在 [earliestDay, latestDay] 且不晚于 dueDay。 */
    private static boolean checkWindow(List<Assignment> asg, JsonNode p) {
        Assignment a = find(asg, p.path("order").asText(""));
        if (a == null) return false;
        int due = p.path("dueDay").asInt(Integer.MAX_VALUE);
        int earliest = p.path("earliestDay").asInt(Integer.MIN_VALUE);
        int latest = p.path("latestDay").asInt(Integer.MAX_VALUE);
        return a.day() <= due && a.day() >= earliest && a.day() <= latest;
    }

    /** 指定订单对不在同一资源同一天。 */
    private static boolean checkMutex(List<Assignment> asg, JsonNode p) {
        List<String> orders = new ArrayList<>();
        p.path("orders").forEach(n -> orders.add(n.asText()));
        if (orders.size() < 2) return true;
        Assignment a = find(asg, orders.get(0)), b = find(asg, orders.get(1));
        if (a == null || b == null) return false;
        return !(a.line().equals(b.line()) && a.day() == b.day());
    }

    /** before 必须早于 after（按 day，再按 startHour）。 */
    private static boolean checkPrecedence(List<Assignment> asg, JsonNode p) {
        Assignment before = find(asg, p.path("before").asText(""));
        Assignment after = find(asg, p.path("after").asText(""));
        if (before == null || after == null) return false;
        if (before.day() != after.day()) return before.day() < after.day();
        return before.startHour() + before.hours() <= after.startHour() + 1e-6;
    }

    /** 指定资源承担工时占比落在 [minShare, maxShare]。 */
    private static boolean checkRatio(List<Assignment> asg, JsonNode p) {
        String line = p.path("line").asText("");
        double min = p.path("minShare").asDouble(0), max = p.path("maxShare").asDouble(1);
        double total = asg.stream().mapToDouble(Assignment::hours).sum();
        double mine = asg.stream().filter(a -> a.line().equals(line)).mapToDouble(Assignment::hours).sum();
        if (total <= 0) return false;
        double share = mine / total;
        return share >= min - 1e-6 && share <= max + 1e-6;
    }

    /** 订单必须排在指定资源。 */
    private static boolean checkAssignment(List<Assignment> asg, JsonNode p) {
        Assignment a = find(asg, p.path("order").asText(""));
        return a != null && a.line().equals(p.path("line").asText(""));
    }
}
