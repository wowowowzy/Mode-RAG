package com.challenger.modetest.controller;

import com.challenger.modetest.domain.TestRun;
import com.challenger.modetest.dto.ActiveRunView;
import com.challenger.modetest.dto.CardSummaryView;
import com.challenger.modetest.dto.DomainView;
import com.challenger.modetest.dto.TaskView;
import com.challenger.modetest.repository.RunRepository;
import com.challenger.modetest.repository.TaskRepository;
import com.challenger.modetest.service.CardSummaryService;
import com.challenger.modetest.service.CompareService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final TaskRepository taskRepository;
    private final RunRepository runRepository;
    private final CompareService compareService;
    private final CardSummaryService cardSummaryService;

    public ApiController(TaskRepository taskRepository, RunRepository runRepository,
                         CompareService compareService, CardSummaryService cardSummaryService) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.compareService = compareService;
        this.cardSummaryService = cardSummaryService;
    }

    @GetMapping("/domains")
    public List<DomainView> domains() {
        CardSummaryView summary = cardSummaryService.summary();
        return List.of(
                new DomainView("planning", "约束规划", "资源 / 时序 / 容量 / 互斥的排布，主结果域",
                        summary.planning(), taskRepository.count("planning")),
                new DomainView("debate", "立场对比", "双方论证与证据评估，泛化性验证",
                        summary.debate(), taskRepository.count("debate")),
                new DomainView("general", "跨域通用", "通用策略与根因诊断，只建卡不评测",
                        summary.general(), taskRepository.count("general")));
    }

    /** 卡片计数的独立入口：前端需要区分「真实为 0」与「读不到」。 */
    @GetMapping("/cards/summary")
    public CardSummaryView cardsSummary() {
        return cardSummaryService.summary();
    }

    @GetMapping("/tasks")
    public List<TaskView> tasks(@RequestParam String domain) {
        return taskRepository.byDomain(domain).stream().map(TaskView::of).toList();
    }

    @GetMapping("/tasks/{taskId}")
    public TaskView task(@PathVariable String taskId) {
        return taskRepository.find(taskId).map(TaskView::of)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
    }

    @PostMapping("/runs")
    public TestRun create(@RequestBody CompareRequest request) {
        return compareService.compare(request.taskId());
    }

    /**
     * 正在生成中的对比。一次双臂要跑 40–130 秒，这段时间记录还没落库，
     * 前端必须靠这个接口才能看到「任务正在跑」而不是以为点了没反应。
     */
    @GetMapping("/runs/active")
    public List<ActiveRunView> activeRuns() {
        return compareService.active();
    }

    @GetMapping("/runs")
    public List<TestRun> runs(@RequestParam(defaultValue = "50") int limit) {
        return runRepository.list(Math.max(1, Math.min(limit, 200)));
    }

    /** id 限定为数字，避免 /runs/active 之类的字面路径被当成 id。 */
    @GetMapping("/runs/{id:\\d+}")
    public TestRun run(@PathVariable long id) {
        return runRepository.find(id)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在: " + id));
    }

    public record CompareRequest(String taskId) {
    }
}
