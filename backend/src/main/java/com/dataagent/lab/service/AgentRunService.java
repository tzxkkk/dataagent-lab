package com.dataagent.lab.service;

import com.dataagent.lab.domain.AgentPlan;
import com.dataagent.lab.domain.AgentRun;
import com.dataagent.lab.domain.PlannerUsage;
import com.dataagent.lab.domain.PlanningToolStep;
import com.dataagent.lab.domain.RunEvidence;
import com.dataagent.lab.domain.RunFeedback;
import com.dataagent.lab.domain.RunStatus;
import com.dataagent.lab.domain.ToolInvocation;
import com.dataagent.lab.domain.ToolResult;
import com.dataagent.lab.domain.TraceEvent;
import com.dataagent.lab.planner.AgentPlanner;
import com.dataagent.lab.planner.PlannerDescriptor;
import com.dataagent.lab.planner.PlannerRegistry;
import com.dataagent.lab.repository.AgentRunRepository;
import com.dataagent.lab.tool.AgentTool;
import com.dataagent.lab.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentRunService {
    private final PlannerRegistry plannerRegistry;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final UserIntentClarifier intentClarifier;
    private final PlanPreviewFactory previewFactory;
    private final AgentRunRepository runRepository;
    private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final Map<String, AgentPlan> pendingPlans = new ConcurrentHashMap<>();

    public AgentRunService(
            PlannerRegistry plannerRegistry,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            UserIntentClarifier intentClarifier,
            PlanPreviewFactory previewFactory,
            AgentRunRepository runRepository
    ) {
        this.plannerRegistry = plannerRegistry;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.intentClarifier = intentClarifier;
        this.previewFactory = previewFactory;
        this.runRepository = runRepository;
    }

    public AgentRun run(String input) {
        return run(input, null);
    }

    public AgentRun run(String input, String plannerMode) {
        validateInput(input);
        long startedAt = System.nanoTime();
        AgentRun run = createRun(input, plannerMode, null);
        try {
            AgentPlan plan = plan(run, false);
            if (!plan.requiresClarification()) {
                execute(run, plan);
            }
        } catch (RuntimeException exception) {
            fail(run, exception);
        } finally {
            run.setDurationMs((System.nanoTime() - startedAt) / 1_000_000);
            runRepository.save(run);
        }
        return run;
    }

    public AgentRun preview(String input, String plannerMode, String parentRunId) {
        validateInput(input);
        if (parentRunId != null && !parentRunId.isBlank()) {
            require(parentRunId);
        }
        long startedAt = System.nanoTime();
        AgentRun run = createRun(input, plannerMode, normalizeNullable(parentRunId));
        try {
            AgentPlanner planner = plannerRegistry.require(run.getPlannerMode());
            if (!planner.handlesClarification()) {
                var clarification = intentClarifier.clarify(run.getEffectiveInput());
                if (clarification.isPresent()) {
                    run.setClarification(clarification.get());
                    run.setStatus(RunStatus.WAITING_FOR_CLARIFICATION);
                    event(run, "CLARIFICATION_REQUIRED", clarification.get().question(),
                            Map.of("options", clarification.get().options(), "source", "deterministic_guard"));
                } else {
                    plan(run, true);
                }
            } else {
                plan(run, true);
            }
        } catch (RuntimeException exception) {
            fail(run, exception);
        } finally {
            run.setDurationMs((System.nanoTime() - startedAt) / 1_000_000);
            runRepository.save(run);
        }
        return run;
    }

    public AgentRun clarify(String id, String resolvedInput) {
        validateInput(resolvedInput);
        AgentRun run = require(id);
        synchronized (run) {
            requireStatus(run, RunStatus.WAITING_FOR_CLARIFICATION);
            long startedAt = System.nanoTime();
            run.setEffectiveInput(resolvedInput.trim());
            run.setClarification(null);
            event(run, "CLARIFICATION_RESOLVED", "用户已选择明确的分析目标",
                    Map.of("effectiveInput", run.getEffectiveInput()));
            try {
                plan(run, true);
            } catch (RuntimeException exception) {
                fail(run, exception);
            } finally {
                run.setDurationMs((System.nanoTime() - startedAt) / 1_000_000);
                runRepository.save(run);
            }
            return run;
        }
    }

    public AgentRun approve(String id) {
        AgentRun run = require(id);
        synchronized (run) {
            requireStatus(run, RunStatus.WAITING_FOR_APPROVAL);
            AgentPlan plan = pendingPlans.remove(id);
            if (plan == null) {
                plan = runRepository.findPendingPlan(id).orElse(null);
            }
            if (plan == null) {
                throw new IllegalStateException("找不到该请求的待审批计划：" + id);
            }
            runRepository.deletePendingPlan(id);
            long startedAt = System.nanoTime();
            event(run, "APPROVAL_RECEIVED", "用户已确认执行计划", Map.of());
            try {
                execute(run, plan);
            } catch (RuntimeException exception) {
                fail(run, exception);
            } finally {
                run.setDurationMs((System.nanoTime() - startedAt) / 1_000_000);
                runRepository.save(run);
            }
            return run;
        }
    }

    public AgentRun revise(String id, String input) {
        AgentRun previous = require(id);
        validateInput(input);
        synchronized (previous) {
            event(previous, "REVISION_REQUESTED", "用户已修改分析请求",
                    Map.of("revisedInput", input.trim()));
        }
        return preview(input, previous.getPlannerMode(), previous.getId());
    }

    public AgentRun recordFeedback(String id, String rating, String reason, String comment) {
        AgentRun run = require(id);
        String normalizedRating = rating == null ? "" : rating.trim().toUpperCase();
        if (!Set.of("UP", "DOWN").contains(normalizedRating)) {
            throw new IllegalArgumentException("反馈类型必须是 UP 或 DOWN");
        }
        if (run.getStatus() != RunStatus.SUCCEEDED && run.getStatus() != RunStatus.FAILED) {
            throw new IllegalArgumentException("只能为已经结束的请求记录反馈");
        }
        synchronized (run) {
            RunFeedback feedback = new RunFeedback(
                    normalizedRating,
                    normalizeNullable(reason),
                    normalizeNullable(comment),
                    Instant.now()
            );
            run.setFeedback(feedback);
            event(run, "FEEDBACK_RECORDED", "已记录用户反馈",
                    Map.of("rating", feedback.rating(), "reason", feedback.reason() == null ? "" : feedback.reason()));
            return run;
        }
    }

    public AgentRun require(String id) {
        return runs.computeIfAbsent(id, runId -> runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("找不到请求：" + runId)));
    }

    private AgentRun createRun(String input, String plannerMode, String parentRunId) {
        AgentPlanner planner = plannerRegistry.require(plannerMode);
        PlannerDescriptor descriptor = planner.descriptor();
        AgentRun run = new AgentRun(UUID.randomUUID().toString(), input.trim(), parentRunId);
        run.setPlannerMode(descriptor.mode());
        run.setPlannerUsage(new PlannerUsage(descriptor.promptVersion(), descriptor.model(), 0, 0));
        runs.put(run.getId(), run);
        event(run, "RUN_CREATED", "已接收分析请求", Map.of(
                "mode", descriptor.mode(),
                "promptVersion", descriptor.promptVersion(),
                "model", descriptor.model(),
                "parentRunId", parentRunId == null ? "" : parentRunId
        ));
        return run;
    }

    private AgentPlan plan(AgentRun run, boolean waitForApproval) {
        AgentPlanner planner = plannerRegistry.require(run.getPlannerMode());
        run.setStatus(RunStatus.PLANNING);
        event(run, "PLANNING_STARTED", "规划器正在选择工具", Map.of());
        AgentPlan plan = planner.plan(run.getEffectiveInput(), step -> tracePlanningStep(run, step));
        if (plan == null || plan.usage() == null) {
            throw new IllegalStateException("规划器必须返回用量信息");
        }
        run.setPlannerUsage(plan.usage());
        if (plan.requiresClarification()) {
            run.setClarification(plan.clarification());
            run.setStatus(RunStatus.WAITING_FOR_CLARIFICATION);
            event(run, "CLARIFICATION_REQUIRED", plan.clarification().question(), Map.of(
                    "options", plan.clarification().options(),
                    "source", "model"
            ));
            return plan;
        }

        validatePlan(plan);
        run.setPlanPreview(previewFactory.create(plan));
        event(run, "PLAN_CREATED", plan.rationale(), Map.of(
                "tools", plan.invocations().stream().map(ToolInvocation::toolName).toList(),
                "promptVersion", plan.usage().promptVersion(),
                "model", plan.usage().model(),
                "inputTokens", plan.usage().inputTokens(),
                "outputTokens", plan.usage().outputTokens()
        ));
        if (waitForApproval) {
            pendingPlans.put(run.getId(), plan);
            runRepository.savePendingPlan(run.getId(), plan);
            run.setStatus(RunStatus.WAITING_FOR_APPROVAL);
            event(run, "PLAN_REVIEW_REQUIRED", "等待用户确认后执行工具", Map.of(
                    "riskLevel", run.getPlanPreview().riskLevel(),
                    "rowLimit", run.getPlanPreview().rowLimit()
            ));
        }
        return plan;
    }

    private void execute(AgentRun run, AgentPlan plan) {
        run.setStatus(RunStatus.RUNNING);
        ToolResult lastResult = null;
        for (ToolInvocation invocation : plan.invocations()) {
            AgentTool tool = toolRegistry.requireValidated(invocation.toolName(), invocation.arguments());
            run.addExecutedTool(tool.name());
            event(run, "TOOL_STARTED", "正在执行工具 " + tool.name(),
                    Map.of("tool", tool.name(), "arguments", invocation.arguments()));
            lastResult = tool.execute(invocation.arguments());
            if (!lastResult.success()) {
                throw new ToolExecutionException(lastResult.summary());
            }
            event(run, "TOOL_SUCCEEDED", lastResult.summary(),
                    Map.of("tool", tool.name(), "result", lastResult.data()));
        }

        run.setOutput(render(lastResult));
        run.setEvidence(evidence(run, lastResult));
        run.setStatus(RunStatus.SUCCEEDED);
        event(run, "RUN_SUCCEEDED", "分析请求执行完成", Map.of(
                "rowCount", run.getEvidence().rowCount(),
                "sourceTables", run.getEvidence().sourceTables()
        ));
    }

    private RunEvidence evidence(AgentRun run, ToolResult result) {
        int rowCount = 0;
        Object rows = result.data().get("rows");
        Object columns = result.data().get("columns");
        if (rows instanceof List<?> rowList) {
            rowCount = rowList.size();
        } else if (columns instanceof List<?> columnList) {
            rowCount = columnList.size();
        }
        return new RunEvidence(
                result.summary(),
                run.getPlanPreview().sourceTables(),
                run.getPlanPreview().filters(),
                run.getPlanPreview().sql(),
                rowCount,
                result.data()
        );
    }

    private void fail(AgentRun run, RuntimeException exception) {
        pendingPlans.remove(run.getId());
        runRepository.deletePendingPlan(run.getId());
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        run.setStatus(RunStatus.FAILED);
        run.setError(message);
        event(run, "RUN_FAILED", message, Map.of());
    }

    private void requireStatus(AgentRun run, RunStatus expected) {
        if (run.getStatus() != expected) {
            throw new IllegalArgumentException(
                    "请求 " + run.getId() + " 当前状态为 " + run.getStatus() + "，预期状态为 " + expected
            );
        }
    }

    private void validateInput(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("分析问题不能为空");
        }
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String render(ToolResult result) {
        if (result == null) {
            return "没有工具结果";
        }
        try {
            return result.summary() + "\n" + objectMapper.writeValueAsString(result.data());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化工具结果", exception);
        }
    }

    private void validatePlan(AgentPlan plan) {
        if (plan == null || plan.invocations() == null || plan.invocations().size() != 1) {
            throw new IllegalStateException("规划器必须返回且只能返回一次工具调用");
        }
        if (plan.usage() == null) {
            throw new IllegalStateException("规划器用量信息不能为空");
        }
        ToolInvocation invocation = plan.invocations().get(0);
        toolRegistry.requireValidated(invocation.toolName(), invocation.arguments());
    }

    private void event(AgentRun run, String type, String message, Map<String, Object> data) {
        TraceEvent event = new TraceEvent(run.getEvents().size() + 1, type, message, Instant.now(), data);
        run.addEvent(event);
        runRepository.save(run);
        runRepository.appendEvent(run.getId(), event);
    }

    private void tracePlanningStep(AgentRun run, PlanningToolStep step) {
        event(
                run,
                step.result().success() ? "PLANNING_TOOL_SUCCEEDED" : "PLANNING_TOOL_FAILED",
                step.result().summary(),
                Map.of(
                        "tool", step.invocation().toolName(),
                        "arguments", step.invocation().arguments(),
                        "result", step.result().data()
                )
        );
    }

    private static final class ToolExecutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ToolExecutionException(String message) {
            super(message);
        }
    }
}
