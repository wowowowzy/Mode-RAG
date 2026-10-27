package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.common.ApiException;
import com.challenger.moderoute.common.util.Gate;
import com.challenger.moderoute.common.util.LibraryVersion;
import com.challenger.moderoute.common.util.TokenEstimator;
import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.dto.EnhanceRequest;
import com.challenger.moderoute.dto.EnhanceResponse;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.entity.Decision;
import com.challenger.moderoute.entity.DecisionOutcome;
import com.challenger.moderoute.entity.enums.CardDomain;
import com.challenger.moderoute.entity.enums.GateAction;
import com.challenger.moderoute.entity.OptionSet;
import com.challenger.moderoute.entity.ScoredCard;
import com.challenger.moderoute.mapper.VectorMapper;
import com.challenger.moderoute.service.AuditService;
import com.challenger.moderoute.service.CardLibraryService;
import com.challenger.moderoute.service.DecideService;
import com.challenger.moderoute.service.EnhanceService;
import com.challenger.moderoute.service.RecallService;
import com.challenger.moderoute.service.RenderService;

import com.challenger.moderoute.common.ApiException;
import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.dto.EnhanceRequest;
import com.challenger.moderoute.dto.EnhanceResponse;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.entity.Decision;
import com.challenger.moderoute.entity.DecisionOutcome;
import com.challenger.moderoute.entity.ScoredCard;
import com.challenger.moderoute.entity.enums.CardDomain;
import com.challenger.moderoute.entity.enums.GateAction;
import com.challenger.moderoute.common.util.TokenEstimator;
import com.challenger.moderoute.service.AuditService;
import com.challenger.moderoute.service.CardLibraryService;
import com.challenger.moderoute.service.DecideService;
import com.challenger.moderoute.service.EnhanceService;
import com.challenger.moderoute.service.RecallService;
import com.challenger.moderoute.service.RenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class EnhanceServiceImpl implements EnhanceService {

    private static final Logger log = LoggerFactory.getLogger(EnhanceServiceImpl.class);

    private static final int MAX_TASK_CODE_POINTS = 4_000;
    private static final int DEFAULT_TOP_K = 8;
    private static final int MIN_TOP_K = 3;
    private static final int MAX_TOP_K = 26;
    private static final int MAX_TRACE_ID_LENGTH = 256;

    private final ModeRouteProperties properties;
    private final CardLibraryService libraryService;
    private final RecallService recallService;
    private final DecideService decideService;
    private final RenderService renderService;
    private final AuditService auditService;

    public EnhanceServiceImpl(ModeRouteProperties properties,
                              CardLibraryService libraryService,
                              RecallService recallService,
                              DecideService decideService,
                              RenderService renderService,
                              AuditService auditService) {
        this.properties = properties;
        this.libraryService = libraryService;
        this.recallService = recallService;
        this.decideService = decideService;
        this.renderService = renderService;
        this.auditService = auditService;
    }

    public EnhanceResponse enhance(EnhanceRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TASK", "请求体不能为空");
        }
        String task = request.task();
        validateTask(task);
        int topK = resolveTopK(request);
        int budgetTokens = resolveBudget(request);
        String traceId = resolveTraceId(request.traceId());

        CardLibrary library = libraryService.library();
        if (library == null || library.cards().isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LIBRARY_NOT_LOADED", "模式库未加载");
        }

        long start = System.nanoTime();
        String overrideMode = null;
        Outcome outcome;
        long recallMs = -1;
        long decideMs = -1;
        EnhanceRequest.OverrideSpec override = request.override();
        if (properties.isOverrideEnabled() && override != null) {
            overrideMode = override.mode() == null ? "" : override.mode().toLowerCase(Locale.ROOT);
            outcome = runOverride(overrideMode, override, task, topK, budgetTokens, library);
        } else {
            PipelineResult result = runPipeline(task, topK, budgetTokens, library);
            outcome = result.outcome();
            recallMs = result.recallMs();
            decideMs = result.decideMs();
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;

        auditService.record(buildAudit(traceId, overrideMode, outcome, library, latencyMs,
                recallMs, decideMs, topK, budgetTokens, task));

        return new EnhanceResponse(
                outcome.prompt(),
                traceId,
                new EnhanceResponse.Meta(
                        outcome.action().name(),
                        outcome.cardId(),
                        round4(outcome.confidence()),
                        library.version()));
    }

    // ---------------------------------------------------------------- 主链路

    private PipelineResult runPipeline(String task, int topK, int budgetTokens, CardLibrary library) {
        List<ScoredCard> candidates;
        long recallStart = System.nanoTime();
        try {
            candidates = recallService.recall(task, topK);
        } catch (RuntimeException e) {
            log.warn("召回失败，转规则降级: {}", e.getMessage());
            long recallMs = (System.nanoTime() - recallStart) / 1_000_000L;
            return new PipelineResult(
                    degrade(task, topK, budgetTokens, library, candidatesOrEmpty(null), "recall: " + e.getMessage()),
                    recallMs, -1);
        }
        long recallMs = (System.nanoTime() - recallStart) / 1_000_000L;
        if (candidates.isEmpty()) {
            return new PipelineResult(
                    new Outcome(GateAction.FALLBACK, null, 0.0, "", candidates, null, null), recallMs, -1);
        }

        DecisionOutcome decided;
        long decideStart = System.nanoTime();
        try {
            decided = decideService.decide(task, candidates, library);
        } catch (RuntimeException e) {
            log.warn("精判失败，转规则降级: {}", e.getMessage());
            long decideMs = (System.nanoTime() - decideStart) / 1_000_000L;
            return new PipelineResult(
                    degrade(task, topK, budgetTokens, library, candidates, "decider: " + e.getMessage()),
                    recallMs, decideMs);
        }
        long decideMs = (System.nanoTime() - decideStart) / 1_000_000L;

        if (decided.action() == GateAction.FALLBACK) {
            return new PipelineResult(
                    new Outcome(GateAction.FALLBACK, null, decided.confidence(), "", candidates, decided.decision(), null),
                    recallMs, decideMs);
        }
        Card card = library.byId().get(decided.cardId());
        String prompt = renderService.render(card, budgetTokens);
        return new PipelineResult(
                new Outcome(GateAction.INJECT, card.id(), decided.confidence(), prompt, candidates, decided.decision(), null),
                recallMs, decideMs);
    }

    private Outcome degrade(String task, int topK, int budgetTokens, CardLibrary library,
                            List<ScoredCard> candidates, String reason) {
        try {
            DecisionOutcome decided = decideService.fallback(task, library, topK);
            if (decided.action() == GateAction.INJECT && decided.cardId() != null) {
                Card card = library.byId().get(decided.cardId());
                String prompt = renderService.render(card, budgetTokens);
                return new Outcome(GateAction.DEGRADED, card.id(), decided.confidence(), prompt,
                        candidates, decided.decision(), reason);
            }
            return new Outcome(GateAction.DEGRADED, null, decided.confidence(), "", candidates,
                    decided.decision(), reason);
        } catch (RuntimeException e) {
            log.error("规则降级也失败: {}", e.getMessage(), e);
            return new Outcome(GateAction.DEGRADED, null, 0.0, "", candidates, null,
                    reason + "; ruleFallback: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- override

    private Outcome runOverride(String mode, EnhanceRequest.OverrideSpec override, String task,
                                int topK, int budgetTokens, CardLibrary library) {
        return switch (mode) {
            case "baseline" -> new Outcome(GateAction.FALLBACK, null, 0.0, "", null, null, "override:baseline");
            case "forcecard" -> forceCard(override.cardId(), budgetTokens, library);
            case "randomcard" -> randomCard(override.seed(), task, budgetTokens, library);
            case "placebo" -> placebo(task, topK, budgetTokens, library);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OVERRIDE",
                    "override.mode 只能是 forceCard / placebo / baseline / randomCard，实际为 " + mode);
        };
    }

    private Outcome forceCard(String cardId, int budgetTokens, CardLibrary library) {
        if (cardId == null || cardId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OVERRIDE", "override.mode=forceCard 时必须提供 cardId");
        }
        Card card = library.byId().get(cardId.trim());
        if (card == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_CARD", "卡片不存在: " + cardId);
        }
        return new Outcome(GateAction.INJECT, card.id(), 1.0,
                renderService.render(card, budgetTokens), null, null, "override:forceCard");
    }

    private Outcome randomCard(Integer seed, String task, int budgetTokens, CardLibrary library) {
        int actualSeed = seed == null ? 0 : seed;
        byte[] digest = sha256(actualSeed + "|" + task);
        int index = Math.floorMod(toInt(digest), library.cards().size());
        Card card = library.cards().get(index);
        return new Outcome(GateAction.INJECT, card.id(), 1.0,
                renderService.render(card, budgetTokens), null, null, "override:randomCard");
    }

    private Outcome placebo(String task, int topK, int budgetTokens, CardLibrary library) {
        int targetChars = 0;
        CardDomain domain = CardDomain.GENERAL;
        try {
            PipelineResult normal = runPipeline(task, topK, budgetTokens, library);
            if (!normal.outcome().prompt().isEmpty()) {
                targetChars = normal.outcome().prompt().length();
                if (normal.outcome().cardId() != null) {
                    domain = library.byId().get(normal.outcome().cardId()).domain();
                }
            }
        } catch (RuntimeException e) {
            log.debug("placebo 目标长度计算失败，改用均值: {}", e.getMessage());
        }
        if (targetChars <= 0) {
            targetChars = averageRenderedLength(library, budgetTokens);
        }
        return new Outcome(GateAction.INJECT, null, 1.0,
                renderService.placebo(domain, targetChars), null, null, "override:placebo");
    }

    private int averageRenderedLength(CardLibrary library, int budgetTokens) {
        int total = 0;
        for (Card card : library.cards()) {
            total += renderService.render(card, budgetTokens).length();
        }
        return total / library.cards().size();
    }

    // ---------------------------------------------------------------- 校验

    private void validateTask(String task) {
        if (task == null || task.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TASK", "task 不能为空");
        }
        int length = task.codePointCount(0, task.length());
        if (length > MAX_TASK_CODE_POINTS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TASK_TOO_LONG",
                    "task 超过 " + MAX_TASK_CODE_POINTS + " 字（实际 " + length + "）");
        }
    }

    private int resolveTopK(EnhanceRequest request) {
        Integer topK = request.options() == null ? null : request.options().topK();
        if (topK == null) {
            return DEFAULT_TOP_K;
        }
        if (topK < MIN_TOP_K || topK > MAX_TOP_K) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OPTIONS",
                    "options.topK 必须在 " + MIN_TOP_K + "–" + MAX_TOP_K + " 之间");
        }
        return topK;
    }

    private int resolveBudget(EnhanceRequest request) {
        Integer budget = request.options() == null ? null : request.options().budgetTokens();
        int min = properties.getRender().getMinBudgetTokens();
        int max = properties.getRender().getMaxBudgetTokens();
        if (budget == null) {
            return properties.getRender().getDefaultBudgetTokens();
        }
        if (budget < min || budget > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OPTIONS",
                    "options.budgetTokens 必须在 " + min + "–" + max + " 之间");
        }
        return budget;
    }

    private String resolveTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = traceId.trim();
        if (trimmed.length() > MAX_TRACE_ID_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TRACE_ID",
                    "traceId 不能超过 " + MAX_TRACE_ID_LENGTH + " 字符");
        }
        return trimmed;
    }

    // ---------------------------------------------------------------- 审计

    private Map<String, Object> buildAudit(String traceId, String overrideMode, Outcome outcome,
                                           CardLibrary library, long latencyMs, long recallMs, long decideMs,
                                           int topK, int budgetTokens, String task) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ts", Instant.now().toString());
        event.put("traceId", traceId);
        event.put("libraryVersion", library.version());
        event.put("embeddingModel", libraryService.embeddingModelName());
        event.put("vectorStore", libraryService.vectorStoreKind());
        event.put("taskSha256", HexFormat.of().formatHex(sha256(task)));
        event.put("taskLength", task.codePointCount(0, task.length()));
        event.put("topK", topK);
        event.put("budgetTokens", budgetTokens);
        event.put("latencyMs", latencyMs);
        if (recallMs >= 0) {
            event.put("recallMs", recallMs);
        }
        if (decideMs >= 0) {
            event.put("decideMs", decideMs);
        }
        if (overrideMode != null && !overrideMode.isEmpty()) {
            event.put("override", overrideMode);
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        if (outcome.candidates() != null) {
            for (ScoredCard candidate : outcome.candidates()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("cardId", candidate.cardId());
                item.put("score", candidate.score());
                candidates.add(item);
            }
        }
        event.put("recall", Map.of("candidates", candidates));

        Decision decision = outcome.decision();
        if (decision != null) {
            List<Map<String, Object>> optionOrder = new ArrayList<>();
            for (OptionSet.Option option : decision.optionList()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("letter", String.valueOf(option.letter()));
                item.put("cardId", option.cardId());
                optionOrder.add(item);
            }
            Map<String, Object> decisionNode = new LinkedHashMap<>();
            decisionNode.put("engine", decision.engine());
            decisionNode.put("optionOrder", optionOrder);
            decisionNode.put("probabilities", roundAll(decision.probabilitiesUnsafe()));
            decisionNode.put("rawProbabilities", roundAll(decision.rawProbabilitiesUnsafe()));
            decisionNode.put("engineConfidence", decision.reportedConfidence());
            decisionNode.put("temperature", decision.temperature());
            decisionNode.put("confidence", round4(decision.maxProbability()));
            decisionNode.put("chosenLetter", String.valueOf(decision.argmaxLetter()));
            event.put("decision", decisionNode);
        } else {
            event.put("decision", null);
        }

        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("action", outcome.action().name());
        gate.put("cardId", outcome.cardId());
        gate.put("confidence", round4(outcome.confidence()));
        event.put("gate", gate);

        Map<String, Object> render = new LinkedHashMap<>();
        render.put("chars", outcome.prompt().length());
        render.put("estimatedTokens", TokenEstimator.estimate(outcome.prompt()));
        render.put("prompt", outcome.prompt());
        event.put("render", render);

        if (outcome.note() != null) {
            event.put("degradedReason", outcome.note());
        }
        return event;
    }

    private static List<Double> roundAll(double[] values) {
        List<Double> out = new ArrayList<>(values.length);
        for (double value : values) {
            out.add(round4(value));
        }
        return out;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private static List<ScoredCard> candidatesOrEmpty(List<ScoredCard> candidates) {
        return candidates == null ? List.of() : candidates;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    private static int toInt(byte[] bytes) {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            value = (value << 8) | (bytes[i] & 0xFF);
        }
        return value;
    }

    private record PipelineResult(Outcome outcome, long recallMs, long decideMs) {
    }

    private record Outcome(
            GateAction action,
            String cardId,
            double confidence,
            String prompt,
            List<ScoredCard> candidates,
            Decision decision,
            String note) {
    }
}
