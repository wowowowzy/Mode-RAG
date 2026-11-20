package com.challenger.modetest.service;

import com.challenger.modetest.client.ChatGateway;
import com.challenger.modetest.client.ModeRouterClient;
import com.challenger.modetest.config.ModeTestProperties;
import com.challenger.modetest.domain.Task;
import com.challenger.modetest.dto.ArmResult;
import com.challenger.modetest.judge.DebateJudge;
import com.challenger.modetest.judge.PlanJudge;
import com.challenger.modetest.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * 三臂实验（P4）：
 * - baseline：只给输出格式要求（plan_v1 / debate_v1 JSON），无推理步骤 —— 控制格式变量；
 * - oracle：注入 acceptableCards[0] 的完整骨架 —— 骨架本身的上限效应；
 * - system：现状（按域召回 + 精判自选卡）—— 真实水平。
 *
 * 三臂共用同一个执行模型与同一组参数，唯一变量是 system 段。
 */
@Service
public class ArmService {

    private static final Set<String> ARMS = Set.of("baseline", "oracle", "system");

    private static final String PLAN_FORMAT =
            "请严格按 plan_v1 输出 JSON：{\"assignments\":[{\"order\",\"line\",\"day\",\"startHour\",\"hours\"}],"
                    + "\"verification\":[{\"cid\",\"passed\",\"detail\"}]}。不要输出解释文字。";
    private static final String DEBATE_FORMAT =
            "请严格按 debate_v1 输出 JSON：{\"stances\":[{\"label\",\"thesis\",\"evidence\",\"objections\","
                    + "\"concessions\"}],\"verdict\"}。不要输出解释文字。";

    private final TaskRepository taskRepository;
    private final ModeRouterClient routerClient;
    private final ChatGateway chatGateway;
    private final DebateJudge debateJudge;
    private final ObjectMapper mapper;
    private final ModeTestProperties.TopK topK;

    public ArmService(TaskRepository taskRepository, ModeRouterClient routerClient,
                      ChatGateway chatGateway, DebateJudge debateJudge,
                      ObjectMapper mapper, ModeTestProperties properties) {
        this.taskRepository = taskRepository;
        this.routerClient = routerClient;
        this.chatGateway = chatGateway;
        this.debateJudge = debateJudge;
        this.mapper = mapper;
        this.topK = properties.getTopk();
    }

    public ArmResult run(String taskId, String arm) {
        Task task = taskRepository.find(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        String name = arm == null ? "" : arm.toLowerCase(Locale.ROOT);
        if (!ARMS.contains(name)) {
            throw new IllegalArgumentException("arm 只能是 baseline / oracle / system，实际为 " + arm);
        }

        String systemPrompt;
        String action = "";
        String cardId = null;
        double confidence = 0;
        String libraryVersion = "";
        long routerMs = 0;

        switch (name) {
            case "baseline" -> systemPrompt = formatOnly(task.domain());
            case "oracle" -> {
                if (task.acceptableCards() == null || task.acceptableCards().isEmpty()) {
                    throw new IllegalArgumentException("任务没有 acceptableCards，无法构造 oracle 臂: " + taskId);
                }
                cardId = task.acceptableCards().get(0);
                long start = System.nanoTime();
                ModeRouterClient.SkeletonResult skeleton = routerClient.cardSkeleton(cardId);
                routerMs = (System.nanoTime() - start) / 1_000_000L;
                systemPrompt = skeleton.prompt();
                action = "ORACLE";
                confidence = 1.0;
            }
            case "system" -> {
                long start = System.nanoTime();
                ModeRouterClient.EnhanceResult enhance = routerClient.enhance(
                        task.prompt(), topK.forDomain(task.domain()), task.domain());
                routerMs = (System.nanoTime() - start) / 1_000_000L;
                systemPrompt = enhance.prompt();
                action = enhance.action();
                cardId = enhance.cardId();
                confidence = enhance.confidence();
                libraryVersion = enhance.libraryVersion();
            }
            default -> throw new IllegalStateException("不可达: " + name);
        }

        ChatGateway.ChatAnswer answer = chatGateway.answer(systemPrompt, task.prompt());

        Double score = null;
        Integer passed = null;
        Integer total = null;
        Boolean parsed = null;
        ObjectNode detail = mapper.createObjectNode();
        if ("planning".equalsIgnoreCase(task.domain())) {
            PlanJudge.Result judged = PlanJudge.judge(task, answer.text());
            score = judged.score();
            passed = judged.passed();
            total = judged.total();
            parsed = judged.parsed();
            var failures = detail.putArray("failures");
            judged.failures().forEach(failures::add);
        } else if ("debate".equalsIgnoreCase(task.domain())) {
            // 单臂无对照对象，用空 baseline 调用同一 judge，只取 oursScore 作为质量分。
            DebateJudge.Result judged = debateJudge.judge(task.prompt(), "", answer.text());
            score = judged.oursScore();
            detail.put("winner", judged.winner());
            if (judged.detail() != null && !judged.detail().isBlank()) {
                detail.put("note", judged.detail());
            }
        }

        return new ArmResult(
                task.taskId(), task.domain(), task.split(), name,
                action, cardId, confidence, libraryVersion,
                routerMs, answer.latencyMs(), answer.promptTokens(), answer.completionTokens(),
                answer.text(), score, passed, total, parsed, detail.toString());
    }

    private static String formatOnly(String domain) {
        return "debate".equalsIgnoreCase(domain) ? DEBATE_FORMAT : PLAN_FORMAT;
    }
}
