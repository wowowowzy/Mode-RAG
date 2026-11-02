package com.challenger.modetest.service.impl;

import com.challenger.modetest.client.ChatGateway;
import com.challenger.modetest.client.ModeRouterClient;
import com.challenger.modetest.domain.Task;
import com.challenger.modetest.domain.TestRun;
import com.challenger.modetest.dto.ActiveRunView;
import com.challenger.modetest.repository.RunRepository;
import com.challenger.modetest.repository.TaskRepository;
import com.challenger.modetest.service.CompareService;
import com.challenger.modetest.service.TaskBusyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 两臂对比：
 * baseline = 任务原文直接给执行模型；
 * ours     = 先取 ModeRouter 的骨架，放进 system 段，再给同一个任务。
 * 两条臂共用同一个 ChatClient 与同一组参数，唯一变量是 system 段。
 *
 * 同一 taskId 同时只允许一次测试：一次双臂跑 40–130 秒，重复点击会产生重复样本。
 * 进行中的任务以内存状态对外暴露（{@link #active()}），因为一次调用要几十秒，
 * 期间数据库里还没有记录，前端若只能看库就完全是黑箱。
 */
@Service
public class CompareServiceImpl implements CompareService {

    private static final Logger log = LoggerFactory.getLogger(CompareServiceImpl.class);

    private final TaskRepository taskRepository;
    private final RunRepository runRepository;
    private final ModeRouterClient routerClient;
    private final ChatGateway chatGateway;

    /** 正在测试中的任务，key 为 taskId；同时充当并发去重的锁。 */
    private final Map<String, InFlight> running = new ConcurrentHashMap<>();

    public CompareServiceImpl(TaskRepository taskRepository,
                              RunRepository runRepository,
                              ModeRouterClient routerClient,
                              ChatGateway chatGateway) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.routerClient = routerClient;
        this.chatGateway = chatGateway;
    }

    @Override
    public TestRun compare(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        // 先查任务，避免把不存在的 taskId 登记成「进行中」
        Task task = taskRepository.find(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        InFlight flight = new InFlight(task.taskId(), task.domain(), Instant.now());
        if (running.putIfAbsent(taskId, flight) != null) {
            throw new TaskBusyException("该任务正在测试中，请等待当前测试完成: " + taskId);
        }
        try {
            return doCompare(task, flight);
        } finally {
            running.remove(taskId, flight);
        }
    }

    @Override
    public List<ActiveRunView> active() {
        long now = System.currentTimeMillis();
        return running.values().stream()
                .map(f -> new ActiveRunView(
                        f.taskId,
                        f.domain,
                        f.phase,
                        f.startedAt.toString(),
                        Math.max(0, now - f.startedAt.toEpochMilli())))
                .sorted(Comparator.comparing(ActiveRunView::startedAt))
                .toList();
    }

    private TestRun doCompare(Task task, InFlight flight) {
        String createdAt = Instant.now().toString();

        flight.phase = "检索推理骨架";
        ModeRouterClient.EnhanceResult enhance;
        try {
            enhance = routerClient.enhance(task.prompt());
        } catch (RuntimeException e) {
            log.warn("增强失败: {}", e.getMessage());
            return runRepository.save(new TestRun(0, task.taskId(), task.domain(), createdAt,
                    "ERROR", null, 0, "", 0, 0, 0, null, null, null, null, null,
                    "增强失败: " + e.getMessage()));
        }

        ChatGateway.ChatAnswer baseline;
        ChatGateway.ChatAnswer ours;
        try {
            flight.phase = "baseline 生成中";
            baseline = chatGateway.answer("", task.prompt());
            flight.phase = "ours 生成中";
            ours = chatGateway.answer(enhance.prompt(), task.prompt());
        } catch (RuntimeException e) {
            log.warn("执行模型调用失败: {}", e.getMessage());
            return runRepository.save(new TestRun(0, task.taskId(), task.domain(), createdAt,
                    enhance.action(), enhance.cardId(), enhance.confidence(), enhance.libraryVersion(),
                    enhance.latencyMs(), 0, 0, null, null, null, null, null,
                    "执行模型失败: " + e.getMessage()));
        }

        TestRun run = new TestRun(0, task.taskId(), task.domain(), createdAt,
                enhance.action(), enhance.cardId(), enhance.confidence(), enhance.libraryVersion(),
                enhance.latencyMs(), baseline.latencyMs(), ours.latencyMs(),
                baseline.promptTokens(), ours.promptTokens(), ours.completionTokens(),
                baseline.text(), ours.text(), emptyNote(baseline.text(), ours.text()));
        return runRepository.save(run);
    }

    private static String emptyNote(String baseline, String ours) {
        boolean emptyBaseline = baseline == null || baseline.isBlank();
        boolean emptyOurs = ours == null || ours.isBlank();
        if (!emptyBaseline && !emptyOurs) {
            return null;
        }
        StringBuilder note = new StringBuilder();
        if (emptyBaseline) {
            note.append("baseline 输出为空");
        }
        if (emptyOurs) {
            if (note.length() > 0) {
                note.append("；");
            }
            note.append("ours 输出为空（模型未返回正文，可能把内容放在 reasoning 字段）");
        }
        return note.toString();
    }

    /** 进行中的任务状态。phase 由工作线程写、HTTP 线程读，因此必须是 volatile。 */
    private static final class InFlight {

        private final String taskId;
        private final String domain;
        private final Instant startedAt;
        private volatile String phase = "已受理";

        private InFlight(String taskId, String domain, Instant startedAt) {
            this.taskId = taskId;
            this.domain = domain;
            this.startedAt = startedAt;
        }
    }
}
