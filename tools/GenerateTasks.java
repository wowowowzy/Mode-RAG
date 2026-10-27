import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 任务集生成器：java tools/GenerateTasks.java
 * 产出 tasks/tasks.jsonl，200 条（planning 120 + debate 80）。
 * 只用 JDK，不引入任何依赖；生成过程完全确定（固定种子）。
 */
public final class GenerateTasks {

    private static final long SEED = 20261027L;
    private static final Path OUT = Path.of("tasks", "tasks.jsonl");

    public static void main(String[] args) throws Exception {
        Random rnd = new Random(SEED);
        List<String> lines = new ArrayList<>();

        int seq = 1;
        for (int family = 1; family <= 8; family++) {
            for (int i = 0; i < 15; i++) {
                String split = i < 4 ? "val" : "test";
                lines.add(planningTask(family, seq++, i, split, rnd));
            }
        }
        for (int family = 1; family <= 8; family++) {
            int valCount = family % 2 == 1 ? 4 : 3;
            for (int i = 0; i < 10; i++) {
                String split = i < valCount ? "val" : "test";
                lines.add(debateTask(family, seq++, i, split));
            }
        }

        Files.createDirectories(OUT.getParent());
        Files.writeString(OUT, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        long val = lines.stream().filter(l -> l.contains("\"split\":\"val\"")).count();
        System.out.println("生成 " + lines.size() + " 条任务（val " + val + " / test " + (lines.size() - val) + "）-> " + OUT.toAbsolutePath());
    }

    // ------------------------------------------------------------------ planning

    private static String planningTask(int family, int seq, int index, String split, Random rnd) {
        String taskId = String.format("plan-%03d", seq);
        int days = 2 + rnd.nextInt(3);
        int lineCount = 2 + rnd.nextInt(2);
        List<String> constraints = new ArrayList<>();
        List<String> cards;
        String prompt;
        String familyName;

        int[] caps = new int[lineCount];
        for (int i = 0; i < lineCount; i++) {
            caps[i] = 6 + 2 * rnd.nextInt(3);
        }

        switch (family) {
            case 1 -> {
                familyName = "capacity-window";
                int[] hours = orderHours(rnd, 4, 7);
                int dueDay = Math.min(days, 2 + rnd.nextInt(Math.max(1, days - 1)));
                prompt = "共有 " + lineCount + " 条产线（" + lineNames(lineCount) + "），每日可用工时分别为 "
                        + capsText(caps) + " 小时，可排产的工作日为第 1–" + days + " 天。"
                        + "有 " + hours.length + " 张订单（" + orderNames(hours.length) + "），所需工时分别为 "
                        + hoursText(hours) + "。每张订单必须一次性排在某条产线的某个工作日内，不得跨天。"
                        + "其中 " + orderName(hours.length - 1) + " 最晚须在第 " + dueDay + " 天完成。"
                        + "请给出满足容量与交期的排产方案。";
                for (int i = 0; i < lineCount; i++) {
                    constraints.add(constraint("C" + (i + 1), "capacity",
                            "产线 " + lineName(i) + " 每日可用工时不超过 " + caps[i] + " 小时",
                            "{\"line\":\"" + lineName(i) + "\",\"perDayHours\":" + caps[i] + "}"));
                }
                constraints.add(constraint("C" + (lineCount + 1), "window",
                        "最后一张订单最晚在第 " + dueDay + " 天完成",
                        "{\"order\":\"" + orderName(hours.length - 1) + "\",\"dueDay\":" + dueDay + "}"));
                cards = index % 5 == 4
                        ? List.of("planning-window-first")
                        : List.of("planning-capacity-first", "planning-window-first");
            }
            case 2 -> {
                familyName = "replan-repair";
                int[] hours = orderHours(rnd, 5, 6);
                boolean incremental = index % 2 == 1;
                if (incremental) {
                    prompt = "已有排程如下：" + existingPlan(hours, days) + "。现在新增一张订单 " + orderName(hours.length)
                            + "，所需工时 " + (3 + rnd.nextInt(3)) + " 小时，必须排在第 1–" + days + " 天内。"
                            + "产线每日可用工时分别为 " + capsText(caps) + "。请只做必要的最小调整，不要推翻原排程。";
                    constraints.add(constraint("C1", "capacity", "新增订单不得使任一产线单日工时超过上限",
                            "{\"lines\":" + jsonArray(lineNames(lineCount)) + ",\"perDayHours\":" + jsonArrayInts(caps) + "}"));
                    constraints.add(constraint("C2", "assignment", "除新增订单外，其余订单的产线与日期不得改变",
                            "{\"order\":\"" + orderName(hours.length) + "\",\"allowedLines\":" + jsonArray(lineNames(lineCount)) + "}"));
                    cards = List.of("planning-incremental-repair");
                } else {
                    prompt = "本轮生产计划整体变更：产线 " + lineNames(lineCount) + " 的每日可用工时调整为 " + capsText(caps)
                            + "，可排产工作日为第 1–" + days + " 天；订单 " + orderNames(hours.length)
                            + " 的工时分别为 " + hoursText(hours) + "。旧排程全部作废，请重新给出完整排程。";
                    for (int i = 0; i < lineCount; i++) {
                        constraints.add(constraint("C" + (i + 1), "capacity",
                                "产线 " + lineName(i) + " 每日可用工时不超过 " + caps[i] + " 小时",
                                "{\"line\":\"" + lineName(i) + "\",\"perDayHours\":" + caps[i] + "}"));
                    }
                    constraints.add(constraint("C" + (lineCount + 1), "assignment",
                            "每张订单只能排一条产线", "{\"orders\":" + jsonArray(orderNames(hours.length)) + "}"));
                    cards = List.of("planning-full-replan");
                }
            }
            case 3 -> {
                familyName = "mutex";
                int[] hours = orderHours(rnd, 4, 5);
                boolean allowSplit = index % 2 == 1;
                if (allowSplit) {
                    prompt = "产线 " + lineNames(lineCount) + " 每日可用工时分别为 " + capsText(caps) + "，工作日为第 1–" + days + " 天。"
                            + "订单 " + orderNames(hours.length) + " 的工时分别为 " + hoursText(hours) + "。"
                            + "订单 O1 与 O2 争用同一台检测设备，任何时段都不得同时进行；允许把订单拆成多段以绕开设备占用。"
                            + "请给出完整排产。";
                    constraints.add(constraint("C1", "mutex", "O1 与 O2 不得在同一时段重叠",
                            "{\"orders\":[\"O1\",\"O2\"],\"resource\":\"R1\"}"));
                    constraints.add(constraint("C2", "capacity", "任一产线单日工时不超过上限",
                            "{\"lines\":" + jsonArray(lineNames(lineCount)) + ",\"perDayHours\":" + jsonArrayInts(caps) + "}"));
                    cards = List.of("planning-mutex-split");
                } else {
                    prompt = "产线 " + lineNames(lineCount) + " 每日可用工时分别为 " + capsText(caps) + "，工作日为第 1–" + days + " 天。"
                            + "订单 " + orderNames(hours.length) + " 的工时分别为 " + hoursText(hours) + "。"
                            + "订单 O1、O2、O3 共用同一台设备，同一时刻只能有一张订单占用该设备，且每张订单不可拆分。"
                            + "请给出满足设备互斥的完整排产。";
                    constraints.add(constraint("C1", "mutex", "O1、O2、O3 不得在时间上重叠",
                            "{\"orders\":[\"O1\",\"O2\",\"O3\"],\"resource\":\"R1\"}"));
                    constraints.add(constraint("C2", "capacity", "任一产线单日工时不超过上限",
                            "{\"lines\":" + jsonArray(lineNames(lineCount)) + ",\"perDayHours\":" + jsonArrayInts(caps) + "}"));
                    cards = List.of("planning-mutex-serialize");
                }
            }
            case 4 -> {
                familyName = "hard-soft";
                int[] hours = orderHours(rnd, 4, 6);
                boolean relax = index % 2 == 1;
                if (relax) {
                    prompt = "订单 " + orderNames(hours.length) + " 的工时分别为 " + hoursText(hours) + "，产线 "
                            + lineNames(lineCount) + " 每日可用工时分别为 " + capsText(caps) + "，工作日为第 1–" + days
                            + " 天。所有约束都不可协商，但若确实冲突，请说明哪一条必须放宽、放宽多少。请给出排产或不可行说明。";
                    constraints.add(constraint("C1", "capacity", "所有产线每日工时不得超过上限",
                            "{\"lines\":" + jsonArray(lineNames(lineCount)) + ",\"perDayHours\":" + jsonArrayInts(caps) + "}"));
                    constraints.add(constraint("C2", "window", "所有订单必须在第 " + days + " 天前完成",
                            "{\"dueDay\":" + days + "}"));
                    cards = List.of("planning-constraint-relaxation");
                } else {
                    prompt = "订单 " + orderNames(hours.length) + " 的工时分别为 " + hoursText(hours) + "。产线 "
                            + lineNames(lineCount) + " 每日可用工时分别为 " + capsText(caps) + "，工作日为第 1–" + days + " 天。"
                            + "硬约束：每张订单必须在其允许产线上完成；软偏好：各产线每日负荷尽量接近。"
                            + "请先保证硬约束，再尽量满足软偏好，并说明哪些偏好没有满足。";
                    constraints.add(constraint("C1", "capacity", "所有产线每日工时不得超过上限",
                            "{\"lines\":" + jsonArray(lineNames(lineCount)) + ",\"perDayHours\":" + jsonArrayInts(caps) + "}"));
                    constraints.add(constraint("C2", "assignment", "O1 只能排在 " + lineName(0) + "，O2 只能排在 " + lineName(lineCount - 1),
                            "{\"pairs\":[[\"O1\",\"" + lineName(0) + "\"],[\"O2\",\"" + lineName(lineCount - 1) + "\"]]}"));
                    cards = List.of("planning-hard-soft-layering");
                }
            }
            case 5 -> {
                familyName = "feasibility-greedy";
                int[] hours = orderHours(rnd, 5, 7);
                boolean feasibility = index % 2 == 0;
                int totalCapacity = 0;
                for (int i = 0; i < lineCount; i++) {
                    totalCapacity += caps[i] * days;
                }
                if (feasibility) {
                    prompt = "产线 " + lineNames(lineCount) + " 每日可用工时分别为 " + capsText(caps) + "，工作日为第 1–" + days
                            + " 天，总可用工时 " + totalCapacity + " 小时。订单 " + orderNames(hours.length) + " 的工时分别为 "
                            + hoursText(hours) + "。请先判断是否存在可行排产，可行再给方案，不可行请直接说明瓶颈在哪里。";
                    constraints.add(constraint("C1", "capacity", "所有产线每日工时不得超过上限",
                            "{\"lines\":" + jsonArray(lineNames(lineCount)) + ",\"perDayHours\":" + jsonArrayInts(caps) + "}"));
                    cards = List.of("planning-feasibility-lower-bound");
                } else {
                    prompt = "产线 " + lineNames(lineCount) + " 每日可用工时分别为 " + capsText(caps) + "，工作日为第 1–" + days
                            + " 天。订单 " + orderNames(hours.length) + " 的工时分别为 " + hoursText(hours)
                            + "。请快速给出一版可执行的排产，然后逐条核对约束，把发现的违例修掉。";
                    constraints.add(constraint("C1", "capacity", "所有产线每日工时不得超过上限",
                            "{\"lines\":" + jsonArray(lineNames(lineCount)) + ",\"perDayHours\":" + jsonArrayInts(caps) + "}"));
                    constraints.add(constraint("C2", "window", "所有订单在第 " + days + " 天前完成",
                            "{\"dueDay\":" + days + "}"));
                    cards = List.of("planning-greedy-then-repair");
                }
            }
            case 6 -> {
                familyName = "ratio";
                boolean proportional = index % 2 == 0;
                int total = 90 + 10 * rnd.nextInt(6);
                if (proportional) {
                    prompt = "总量 " + total + " 工时需要在 " + lineNames(lineCount) + " 之间按 "
                            + ratioText(lineCount) + " 的比例分配，每条产线每日上限分别为 " + capsText(caps)
                            + "，工作日 " + days + " 天。请给出各产线的分配结果，并说明实际比例与目标比例的偏差。";
                    constraints.add(constraint("C1", "ratio", "各产线工时按 " + ratioText(lineCount) + " 分配",
                            "{\"total\":" + total + ",\"targets\":" + ratioJson(lineCount) + "}"));
                    cards = List.of("planning-ratio-proportional");
                } else {
                    int floor = 10 + rnd.nextInt(5);
                    prompt = "总量 " + total + " 工时需要在 " + lineNames(lineCount) + " 之间分配，每条产线至少 " + floor
                            + " 小时，且不超过各自 " + days + " 天的总容量（每日上限分别为 " + capsText(caps)
                            + " 小时）。请尽量让各产线总量接近，并给出最终极差。";
                    constraints.add(constraint("C1", "ratio", "各产线分配量尽量均衡",
                            "{\"total\":" + total + ",\"mode\":\"maxmin\",\"minPerLine\":" + floor + "}"));
                    constraints.add(constraint("C2", "capacity", "各产线分配量不超过 " + days + " 天总容量",
                            "{\"lines\":" + jsonArray(lineNames(lineCount)) + ",\"perDayHours\":" + jsonArrayInts(caps) + ",\"days\":" + days + "}"));
                    cards = List.of("planning-ratio-maxmin");
                }
            }
            case 7 -> {
                familyName = "precedence";
                int[] hours = orderHours(rnd, 4, 5);
                boolean backward = index % 2 == 1;
                if (backward) {
                    prompt = "订单 " + orderNames(hours.length) + " 的工时分别为 " + hoursText(hours) + "，产线 "
                            + lineNames(lineCount) + " 每日可用工时分别为 " + capsText(caps) + "。所有订单必须在第 " + days
                            + " 天结束前完成。请从交期倒推每张订单的最晚开工时间，再给出排产方案。";
                    constraints.add(constraint("C1", "window", "所有订单在第 " + days + " 天前完成",
                            "{\"dueDay\":" + days + "}"));
                    constraints.add(constraint("C2", "capacity", "所有产线每日工时不得超过上限",
                            "{\"lines\":" + jsonArray(lineNames(lineCount)) + ",\"perDayHours\":" + jsonArrayInts(caps) + "}"));
                    cards = List.of("planning-precedence-backward");
                } else {
                    prompt = "订单 " + orderNames(hours.length) + " 的工时分别为 " + hoursText(hours) + "，产线 "
                            + lineNames(lineCount) + " 每日可用工时分别为 " + capsText(caps) + "，工作日第 1–" + days + " 天。"
                            + "工艺要求：O1 完成后才能开始 O2，O2 完成后才能开始 O3，其余订单无依赖。请给出满足依赖顺序的排产。";
                    constraints.add(constraint("C1", "precedence", "O1 先于 O2，O2 先于 O3",
                            "{\"chain\":[\"O1\",\"O2\",\"O3\"]}"));
                    constraints.add(constraint("C2", "capacity", "所有产线每日工时不得超过上限",
                            "{\"lines\":" + jsonArray(lineNames(lineCount)) + ",\"perDayHours\":" + jsonArrayInts(caps) + "}"));
                    cards = List.of("planning-precedence-topo");
                }
            }
            default -> {
                familyName = "objective";
                int[] hours = orderHours(rnd, 4, 6);
                boolean makespan = index % 2 == 0;
                if (makespan) {
                    prompt = "订单 " + orderNames(hours.length) + " 的工时分别为 " + hoursText(hours) + "，产线 "
                            + lineNames(lineCount) + " 每日可用工时分别为 " + capsText(caps) + "，工作日第 1–" + days + " 天。"
                            + "目标是在满足工期的前提下让全部订单尽早完成。请给出排产并说明总完工时间与关键订单。";
                    constraints.add(constraint("C1", "capacity", "所有产线每日工时不得超过上限",
                            "{\"lines\":" + jsonArray(lineNames(lineCount)) + ",\"perDayHours\":" + jsonArrayInts(caps) + "}"));
                    cards = List.of("planning-makespan-first");
                } else {
                    prompt = "订单 " + orderNames(hours.length) + " 的工时分别为 " + hoursText(hours) + "，产线 "
                            + lineNames(lineCount) + " 每日可用工时分别为 " + capsText(caps) + "，工作日第 1–" + days + " 天。"
                            + "目标是各产线每日负荷尽量均衡，避免任何产线长期满负荷。请给出排产并说明负荷极差。";
                    constraints.add(constraint("C1", "capacity", "所有产线每日工时不得超过上限",
                            "{\"lines\":" + jsonArray(lineNames(lineCount)) + ",\"perDayHours\":" + jsonArrayInts(caps) + "}"));
                    cards = List.of("planning-balance-load");
                }
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("family", familyName);
        meta.put("split", split);
        meta.put("difficulty", index % 3 == 0 ? "hard" : "medium");
        meta.put("variant", String.valueOf(index % 2));

        return "{" + kv("taskId", str(taskId))
                + "," + kv("domain", str("planning"))
                + "," + kv("prompt", str(prompt))
                + "," + kv("constraints", "[" + String.join(",", constraints) + "]")
                + "," + kv("stances", "[]")
                + "," + kv("acceptableCards", jsonArray(cards))
                + "," + kv("meta", objFromMap(meta))
                + "}";
    }

    // -------------------------------------------------------------------- debate

    private static String debateTask(int family, int seq, int index, String split) {
        String taskId = String.format("deb-%03d", seq);
        Topic topic = DEBATE_TOPICS.get(family - 1).get(index % 5);
        boolean secondVoice = index >= 5;
        String prompt;
        List<String> cards;
        String familyName;

        switch (family) {
            case 1 -> {
                familyName = "steelman";
                prompt = secondVoice
                        ? "围绕「" + topic.title + "」有 A、B 两种立场。请先把 A 方论证强化到最强版本，再针对其依赖的前提提出反驳，最后判断 A 方还剩多少说服力。"
                        : "围绕「" + topic.title + "」有 A、B 两种立场。请为双方各构造最强论证，再判断哪一方更有理。";
                cards = secondVoice
                        ? List.of("debate-steelman-then-attack")
                        : List.of("debate-steelman-each");
            }
            case 2 -> {
                familyName = "premise";
                prompt = secondVoice
                        ? "下面这段论证支持「" + topic.title + "」：" + topic.argument + " 请找出其中未明说的关键假设，并判断这些假设是否成立。"
                        : "A 方主张「" + topic.positionA + "」，B 方主张「" + topic.positionB + "」。双方论据看起来相似，请定位导致结论不同的关键前提差异。";
                cards = secondVoice
                        ? List.of("debate-assumption-audit")
                        : List.of("debate-premise-gap");
            }
            case 3 -> {
                familyName = "evidence";
                prompt = secondVoice
                        ? "关于「" + topic.title + "」，A 方给出了若干案例，B 方给出了若干统计数据。请判断现有证据是否足以支持任一方，并指出缺哪些关键证据。"
                        : "关于「" + topic.title + "」，A 方与 B 方各自给出了一组证据。请按证据强度分层比较，判断哪一方的证据更可靠。";
                cards = secondVoice
                        ? List.of("debate-evidence-gap")
                        : List.of("debate-evidence-hierarchy");
            }
            case 4 -> {
                familyName = "fact-value";
                prompt = secondVoice
                        ? "针对「" + topic.title + "」，A 方与 B 方的最终目标一致，但对安全与效率的排序不同。请说明两种排序各自的代价，并给出条件式建议。"
                        : "围绕「" + topic.title + "」，双方争论中既有事实判断也有价值取向。请把两类分歧分开处理，再给出结论。";
                cards = secondVoice
                        ? List.of("debate-value-tradeoff")
                        : List.of("debate-fact-value-split");
            }
            case 5 -> {
                familyName = "attack";
                prompt = secondVoice
                        ? "「" + topic.positionA + "」这一主张支持者众多。请判断它是否可被证伪、现有信息是否已经证伪它。"
                        : "有人提出「" + topic.positionA + "」。请以红队方式找出该方案最可能失败的路径，并按严重程度排序。";
                cards = secondVoice
                        ? List.of("debate-falsification")
                        : List.of("debate-red-team");
            }
            case 6 -> {
                familyName = "evaluation";
                prompt = secondVoice
                        ? "对「" + topic.title + "」有多个候选方案，常规条件下都可行。请比较它们在极端情形下的鲁棒性，指出各自的失效边界。"
                        : "需要在多个候选方案之间选择「" + topic.title + "」的做法。请先确定评价维度与权重，再给出排序。";
                cards = secondVoice
                        ? List.of("debate-scenario-stress")
                        : List.of("debate-criteria-first");
            }
            case 7 -> {
                familyName = "structure";
                prompt = secondVoice
                        ? "下面这段论证支持「" + topic.title + "」：" + topic.argument + " 请核对其中是否存在影响结论有效性的逻辑谬误。"
                        : "下面这段论证支持「" + topic.title + "」：" + topic.argument + " 请拆解它的论证链条，找出最薄弱的一环。";
                cards = secondVoice
                        ? List.of("debate-fallacy-check")
                        : List.of("debate-claim-premise-chain");
            }
            default -> {
                familyName = "resolution";
                prompt = secondVoice
                        ? "关于「" + topic.title + "」，支持 A 方的证据目前只到案例层级。请判断是否已达到行动门槛，给出行动或继续观察的建议。"
                        : "关于「" + topic.title + "」，双方争执不下。请先找出双方都接受的共识，再把真正分歧收缩到最小集合。";
                cards = secondVoice
                        ? List.of("debate-decision-threshold")
                        : List.of("debate-common-ground");
            }
        }

        String stances = "[" + stance("A", topic.positionA) + "," + stance("B", topic.positionB) + "]";
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("family", familyName);
        meta.put("split", split);
        meta.put("difficulty", index % 2 == 0 ? "medium" : "hard");
        meta.put("variant", secondVoice ? "1" : "0");

        return "{" + kv("taskId", str(taskId))
                + "," + kv("domain", str("debate"))
                + "," + kv("prompt", str(prompt))
                + "," + kv("constraints", "[]")
                + "," + kv("stances", stances)
                + "," + kv("acceptableCards", jsonArray(cards))
                + "," + kv("meta", objFromMap(meta))
                + "}";
    }

    private record Topic(String title, String positionA, String positionB, String argument) {
    }

    private static final List<List<Topic>> DEBATE_TOPICS = List.of(
            List.of(
                    new Topic("远程办公是否应成为默认工作方式", "远程办公应成为默认方式", "办公室协作仍是效率上限", ""),
                    new Topic("城市是否应全面限制燃油车", "应尽快全面限制", "应渐进过渡而非限制", ""),
                    new Topic("大学是否应取消期末考试", "应取消期末考试", "考试仍是必要的检验手段", ""),
                    new Topic("短视频平台是否应强制青少年模式", "应强制默认开启", "应由家长自主选择", ""),
                    new Topic("公司是否应公开全员薪酬", "应公开全员薪酬", "薪酬应保持私下约定", "")),
            List.of(
                    new Topic("是否应给基层员工更大的决策权", "应下放决策权", "应保持集中决策", "一线更了解真实情况，因此下放决策权一定能提升效率。"),
                    new Topic("新系统是否应一次性替换旧系统", "应一次性切换", "应逐步迁移", "旧系统维护成本高，所以一次性替换必然更省钱。"),
                    new Topic("是否应对所有上线变更强制双人复核", "应强制双人复核", "只对高风险变更复核", "事故都发生在变更环节，因此双人复核可以消除事故。"),
                    new Topic("是否应把绩效与代码行数挂钩", "应与代码行数挂钩", "应按结果而非行数考核", "代码行数可量化，因此按行数考核最公平。"),
                    new Topic("是否应禁止在生产环境手工操作", "应全面禁止手工操作", "应允许受控的手工操作", "自动化更可靠，所以任何手工操作都应当被禁止。")),
            List.of(
                    new Topic("某城市是否应扩建地铁", "应扩建地铁", "应优先发展公交", ""),
                    new Topic("某公司是否应自研数据库", "应自研数据库", "应使用成熟开源方案", ""),
                    new Topic("是否应给学生布置更多作业", "应增加作业量", "应减少作业量", ""),
                    new Topic("是否应允许员工每周一天带薪学习", "应允许", "不应占用工作时间", ""),
                    new Topic("是否应对进口商品加征关税", "应加征关税", "应维持现有关税", "")),
            List.of(
                    new Topic("是否应允许在人流密集区使用人脸识别", "应允许以提升安全", "应禁止以保护隐私", ""),
                    new Topic("是否应给高风险岗位更高的薪酬", "应显著提高", "应按统一标准", ""),
                    new Topic("是否应强制企业披露碳排放数据", "应强制披露", "应自愿披露", ""),
                    new Topic("是否应限制算法推荐的使用", "应严格限制", "应由用户自行选择", ""),
                    new Topic("是否应把医疗资源向重症倾斜", "应优先重症", "应优先预防", "")),
            List.of(
                    new Topic("全面推行四天工作制", "全面推行四天工作制可以提高产出", "四天工作制会削弱交付能力", ""),
                    new Topic("用大模型替代初级客服", "应全面替代初级客服", "应保留人工兜底", ""),
                    new Topic("把核心业务迁到公有云", "应全面上云", "应保留自建机房", ""),
                    new Topic("取消所有中间管理层", "取消中间层能提升效率", "中间层承担必要协调", ""),
                    new Topic("允许员工自由选择技术栈", "应允许自由选择", "应统一技术栈", "")),
            List.of(
                    new Topic("如何选择新的订单系统", "开源方案", "商业方案", ""),
                    new Topic("如何安排明年的招聘计划", "扩大校招", "加大社招", ""),
                    new Topic("如何降低客服响应时长", "增加人手", "优化知识库", ""),
                    new Topic("如何提升代码评审质量", "增加评审人数", "缩短单次评审规模", ""),
                    new Topic("如何选择模型供应商", "单一供应商", "多家供应商", "")),
            List.of(
                    new Topic("是否应把研发预算集中到一个项目", "应集中投入", "应分散投入", "资源集中才能突破，所以我们应当把所有研发预算集中到一个项目上。"),
                    new Topic("是否应取消所有的审批流程", "应取消审批", "应保留关键审批", "审批不产生价值，因此所有审批都应当取消。"),
                    new Topic("是否应要求所有需求先写完整文档", "应要求完整文档", "应采用轻量文档", "文档越完整项目越成功，所以必须要求完整文档。"),
                    new Topic("是否应把所有服务拆成微服务", "应全部拆分", "应按需拆分", "微服务更灵活，因此所有服务都应该拆成微服务。"),
                    new Topic("是否应用加班时长衡量投入", "应用加班时长衡量", "应看产出而非时长", "加班越多投入越大，因此加班时长可以代表贡献。")),
            List.of(
                    new Topic("是否应在明年引入自动化测试", "应引入自动化测试", "应继续手工测试", ""),
                    new Topic("是否应把客服外包", "应外包客服", "应保留自建团队", ""),
                    new Topic("是否应提高产品售价", "应提高售价", "应维持价格", ""),
                    new Topic("是否应开放核心接口给第三方", "应开放接口", "应保持封闭", ""),
                    new Topic("是否应把办公室搬至郊区", "应搬至郊区", "应留在市中心", ""))
    );

    // -------------------------------------------------------------------- utils

    private static String stance(String label, String position) {
        return "{" + kv("label", str(label)) + "," + kv("position", str(position)) + "}";
    }

    private static int[] orderHours(Random rnd, int min, int max) {
        int n = min + rnd.nextInt(max - min + 1);
        int[] hours = new int[n];
        for (int i = 0; i < n; i++) {
            hours[i] = 2 + rnd.nextInt(6);
        }
        return hours;
    }

    private static String existingPlan(int[] hours, int days) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hours.length; i++) {
            if (i > 0) {
                sb.append("；");
            }
            sb.append(orderName(i)).append(" 排在第 ").append(1 + i % days).append(" 天 ").append(lineName(i % 2));
        }
        return sb.toString();
    }

    private static String lineName(int index) {
        return "L" + (index + 1);
    }

    private static String orderName(int index) {
        return "O" + (index + 1);
    }

    private static List<String> lineNames(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add(lineName(i));
        }
        return names;
    }

    private static List<String> orderNames(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add(orderName(i));
        }
        return names;
    }

    private static String capsText(int[] caps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < caps.length; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(caps[i]);
        }
        return sb.toString();
    }

    private static String hoursText(int[] hours) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hours.length; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(hours[i]).append(" 小时");
        }
        return sb.toString();
    }

    private static String ratioText(int count) {
        if (count == 2) {
            return "5:5";
        }
        return "5:3:2";
    }

    private static String ratioJson(int count) {
        if (count == 2) {
            return "{\"L1\":0.5,\"L2\":0.5}";
        }
        return "{\"L1\":0.5,\"L2\":0.3,\"L3\":0.2}";
    }

    private static String constraint(String cid, String type, String text, String params) {
        return "{" + kv("cid", str(cid)) + "," + kv("type", str(type)) + "," + kv("text", str(text))
                + "," + kv("params", params) + "}";
    }

    private static String jsonArray(List<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String value : values) {
            quoted.add(str(value));
        }
        return "[" + String.join(",", quoted) + "]";
    }

    private static String jsonArrayInts(int[] values) {
        List<String> items = new ArrayList<>();
        for (int value : values) {
            items.add(String.valueOf(value));
        }
        return "[" + String.join(",", items) + "]";
    }

    private static String objFromMap(Map<String, String> map) {
        List<String> parts = new ArrayList<>();
        map.forEach((k, v) -> parts.add(kv(k, str(v))));
        return "{" + String.join(",", parts) + "}";
    }

    private static String kv(String key, String value) {
        return str(key) + ":" + value;
    }

    private static String str(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
