package com.challenger.modetest.service;

import com.challenger.modetest.domain.Task;
import com.challenger.modetest.domain.TestRun;
import com.challenger.modetest.judge.DebateJudge;
import com.challenger.modetest.judge.PlanJudge;
import com.challenger.modetest.repository.RunRepository;
import com.challenger.modetest.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

/**
 * 判定器：planning 用约束自动核验，debate 用 LLM-as-judge。
 * 结果写回 test_run 的判定列，返回回填后的记录。
 */
@Service
public class JudgeService {

    private final TaskRepository taskRepository;
    private final RunRepository runRepository;
    private final DebateJudge debateJudge;
    private final ObjectMapper mapper;

    public JudgeService(TaskRepository taskRepository, RunRepository runRepository,
                        DebateJudge debateJudge, ObjectMapper mapper) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.debateJudge = debateJudge;
        this.mapper = mapper;
    }

    public TestRun judge(long runId) {
        TestRun run = runRepository.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在: " + runId));
        Task task = taskRepository.find(run.taskId())
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + run.taskId()));

        if ("planning".equalsIgnoreCase(task.domain())) {
            return judgePlanning(run, task);
        }
        if ("debate".equalsIgnoreCase(task.domain())) {
            return judgeDebate(run, task);
        }
        return runRepository.updateJudge(runId, null, null, null, null,
                "domain=" + task.domain() + " 不参与判定").orElse(run);
    }

    private TestRun judgePlanning(TestRun run, Task task) {
        PlanJudge.Result baseline = PlanJudge.judge(task, run.baselineText());
        PlanJudge.Result ours = PlanJudge.judge(task, run.oursText());

        ObjectNode detail = mapper.createObjectNode();
        detail.put("domain", "planning");
        detail.set("baseline", describe(baseline));
        detail.set("ours", describe(ours));

        return runRepository.updateJudge(run.id(), baseline.score(), ours.score(),
                        baseline.passed(), ours.passed(), detail.toString())
                .orElse(run);
    }

    private ObjectNode describe(PlanJudge.Result result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("parsed", result.parsed());
        node.put("passed", result.passed());
        node.put("total", result.total());
        node.put("score", result.score());
        var failures = node.putArray("failures");
        result.failures().forEach(failures::add);
        return node;
    }

    private TestRun judgeDebate(TestRun run, Task task) {
        DebateJudge.Result result = debateJudge.judge(task.prompt(), run.baselineText(), run.oursText());

        ObjectNode detail = mapper.createObjectNode();
        detail.put("domain", "debate");
        detail.put("winner", result.winner());
        if (result.detail() != null && !result.detail().isBlank()) {
            detail.put("note", result.detail());
        }

        return runRepository.updateJudge(run.id(), result.baselineScore(), result.oursScore(),
                        null, null, detail.toString())
                .orElse(run);
    }
}
